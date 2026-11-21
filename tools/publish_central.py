"""Publish signed SFCompose artifacts without copying Maven credentials into Gradle files."""

import argparse
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


REPOSITORY = Path(__file__).resolve().parents[1]


def read_credentials(path, server_id=None):
    root = ET.parse(path).getroot()
    servers = []
    for element in root.iter():
        if element.tag.rsplit('}', 1)[-1] != 'server':
            continue
        fields = {child.tag.rsplit('}', 1)[-1]: (child.text or '').strip() for child in element}
        if server_id is None or fields.get('id') == server_id:
            if fields.get('username') and fields.get('password'):
                servers.append((fields['username'], fields['password']))
    if len(servers) != 1:
        raise ValueError('Select exactly one credential pair with --server-id from settings.xml.')
    username, password = servers[0]
    if password.startswith('{') or '${' in username or '${' in password:
        raise ValueError('Use a Central Portal user-token pair, not Maven-encrypted or unresolved credentials.')
    return username, password


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--settings', type=Path, required=True, help='Local Maven settings.xml with a Central Portal token')
    parser.add_argument('--server-id', help='Required when settings.xml contains multiple credential pairs')
    parser.add_argument('--signing-key', type=Path, required=True, help='ASCII-armored private signing key outside the repository')
    parser.add_argument('--signing-password-file', type=Path, help='UTF-8 key passphrase file outside the repository')
    parser.add_argument('--local', action='store_true', help='Publish only to build/sfcompose-repository; no upload')
    parser.add_argument('--catalog', action='store_true', help='Include the public catalog and its build-data ZIP')
    args = parser.parse_args()

    username, password = read_credentials(args.settings, args.server_id)
    key = args.signing_key.read_text(encoding='utf-8')
    if '-----BEGIN PGP PRIVATE KEY BLOCK-----' not in key:
        raise ValueError('--signing-key must contain an ASCII-armored private PGP key.')
    environment = os.environ.copy()
    environment['ORG_GRADLE_PROJECT_mavenCentralUsername'] = username
    environment['ORG_GRADLE_PROJECT_mavenCentralPassword'] = password
    environment['ORG_GRADLE_PROJECT_signingInMemoryKey'] = key
    if args.signing_password_file:
        environment['ORG_GRADLE_PROJECT_signingInMemoryKeyPassword'] = args.signing_password_file.read_text(encoding='utf-8').rstrip('\r\n')

    projects = [':sfcompose', ':sfcompose-gradle-plugin']
    if args.catalog:
        projects.append(':sfcompose-catalog')
    task = 'publishAllPublicationsToSfcomposeLocalRepository' if args.local else 'publishToMavenCentral'
    wrapper = REPOSITORY / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = [str(wrapper), *(project + ':' + task for project in projects),
               '-PsfcomposeSigning=true', '--no-configuration-cache', '--console=plain']
    if not args.local:
        command.append('-PsfcomposeCentral=true')
    # The signing key must not be serialized into a reusable Gradle configuration-cache entry.
    subprocess.run(command, cwd=REPOSITORY, env=environment, shell=False, check=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
