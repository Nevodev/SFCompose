from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import publish_central


class PublishingCredentialsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.settings = self.directory / 'settings.xml'

    def test_sonatype_xml_namespace_and_token_label_are_independent(self):
        self.settings.write_text(
            '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><servers>'
            '<server><id>central</id><username>token-user</username><password>token-secret</password></server>'
            '</servers></settings>', encoding='utf-8')
        self.assertEqual(('token-user', 'token-secret'), publish_central.read_credentials(self.settings))

    def test_multiple_servers_require_explicit_selection(self):
        self.settings.write_text(
            '<settings><servers>'
            '<server><id>one</id><username>first</username><password>first-secret</password></server>'
            '<server><id>two</id><username>second</username><password>second-secret</password></server>'
            '</servers></settings>', encoding='utf-8')
        with self.assertRaises(ValueError):
            publish_central.read_credentials(self.settings)
        self.assertEqual(('second', 'second-secret'), publish_central.read_credentials(self.settings, 'two'))

    def test_maven_encryption_and_unresolved_placeholders_are_rejected(self):
        for value in ['{encrypted}', '${env.MAVEN_TOKEN}']:
            self.settings.write_text(
                '<settings><servers><server><username>user</username>'
                f'<password>{value}</password></server></servers></settings>', encoding='utf-8')
            with self.assertRaises(ValueError):
                publish_central.read_credentials(self.settings)

    def test_local_signing_never_selects_upload_or_puts_secrets_in_arguments(self):
        self.settings.write_text(
            '<settings><servers><server><username>token-user</username>'
            '<password>token-secret</password></server></servers></settings>', encoding='utf-8')
        key = self.directory / 'key.asc'
        key.write_text('-----BEGIN PGP PRIVATE KEY BLOCK-----\nfixture\n', encoding='utf-8')
        password = self.directory / 'password.txt'
        password.write_text('key-secret\n', encoding='utf-8')
        arguments = ['publish_central.py', '--local', '--settings', str(self.settings),
                     '--signing-key', str(key), '--signing-password-file', str(password),
                     '--modules', 'sfcompose', 'sfcompose-gradle-plugin']
        with patch('sys.argv', arguments), patch('publish_central.subprocess.run') as execute:
            publish_central.main()
        command = execute.call_args.args[0]
        self.assertTrue(all('publishToMavenCentral' not in argument for argument in command))
        self.assertNotIn(':sfcompose-catalog:publishAllPublicationsToSfcomposeLocalRepository', command)
        self.assertIn('--no-configuration-cache', command)
        self.assertIn('-PsfcomposeSigning=true', command)
        self.assertNotIn('-PsfcomposeCentral=true', command)
        environment = execute.call_args.kwargs['env']
        self.assertEqual('token-secret', environment['ORG_GRADLE_PROJECT_mavenCentralPassword'])
        self.assertEqual('key-secret', environment['ORG_GRADLE_PROJECT_signingInMemoryKeyPassword'])
        for secret in ['token-user', 'token-secret', 'key-secret', 'PRIVATE KEY']:
            self.assertFalse(any(secret in argument for argument in command))

    def test_default_publication_includes_private_catalog(self):
        key = self.directory / 'key.asc'
        key.write_text('-----BEGIN PGP PRIVATE KEY BLOCK-----\nfixture\n', encoding='utf-8')
        arguments = ['publish_central.py', '--local', '--settings', str(self.settings), '--signing-key', str(key)]
        with patch('sys.argv', arguments), patch('publish_central.read_credentials', return_value=('user', 'secret')), \
                patch('publish_central.subprocess.run') as execute:
            publish_central.main()
        command = execute.call_args.args[0]
        for module in ['sfcompose-catalog', 'sfcompose-catalog-private']:
            self.assertIn(':' + module + ':publishAllPublicationsToSfcomposeLocalRepository', command)

    def test_private_catalog_can_be_uploaded_without_reuploading_other_modules(self):
        key = self.directory / 'key.asc'
        key.write_text('-----BEGIN PGP PRIVATE KEY BLOCK-----\nfixture\n', encoding='utf-8')
        arguments = ['publish_central.py', '--settings', str(self.settings), '--signing-key', str(key),
                     '--modules', 'sfcompose-catalog-private']
        with patch('sys.argv', arguments), patch('publish_central.read_credentials', return_value=('user', 'secret')), \
                patch('publish_central.subprocess.run') as execute:
            publish_central.main()
        command = execute.call_args.args[0]
        self.assertEqual([':sfcompose-catalog-private:publishToMavenCentral'],
                         [argument for argument in command if argument.startswith(':')])
        self.assertIn('-PsfcomposeCentral=true', command)
        self.assertNotIn('publishAndReleaseToMavenCentral', ' '.join(command))


if __name__ == '__main__':
    unittest.main()
