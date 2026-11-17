# Original rejection of missing dynamic anchors

Five retained system symbols contain two attachment records but only one dynamic
anchor. The first record references anchor 0; the second references nonexistent
anchor 1. The original BVG data, including that invalid reference, is retained
unchanged in these test fixtures.
The original BVG `da` nodes have no child geometry (`children: []`), so CoreUI
uses their single `data-d` entry. Full source nodes and SHA-256 values are retained
in `invalid-dynamic-anchor-source-nodes.json` beside the native reference.

This is an original CoreUI rejection, not an unsupported Android feature.
`initFromSVGString:attachmentData:` compares the anchor index to `numEntries` at
`18f2412fc` and branches on `>=` at `18f241310` to the error block `18f2414c0`.
That block calls `_setupEmpty` at `18f2414e0`. The original `_setupEmpty` method
(`18f240de8`) clears both the attachment pointer and count; previously parsed
valid records are also discarded. Draw clip keyframes remain available.

`invalid-dynamic-anchor-store-oracle.json` contains four executions of the
original ARM64 comparison/error block and `_setupEmpty`, with the store initially
containing one valid record. Foundation `numEntries` and ownership operations are
shimmed; comparison, branch, error cleanup sequence and empty-store writes are
original code. Index 0 of 1 retains count 1; indices 1 or 2 of 1, and 0 of 0,
execute `_setupEmpty` and leave count 0 / pointer 0.

Producer and full reference are retained in
`D:/MACOS/analysis/system/dyld/CoreUI/` as
`sfcompose_attachment_invalid_anchor_oracle.py` and
`invalid-dynamic-anchor-store-oracle.json`.
