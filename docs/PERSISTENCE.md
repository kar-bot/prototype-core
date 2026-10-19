# Core world state

Each server/world uses `data/prototype/core-state.json` relative to the world
directory. The integrated server uses its selected save's directory. Core never
reads, migrates, copies, or deletes an addon's progression files.

`StateStore` accepts a directory rather than relying on the process working
directory. Its persistence classes are implementation details; addons should use
the public authorization API instead of editing this JSON.

Schema version 1 stores stable UUIDs and namespaced identifiers:

```json
{
  "schemaVersion": 1,
  "revision": 42,
  "players": {
    "c5740000-34dc-4c10-a5db-ffa467654a71": {
      "classBanned": true,
      "hybridEnabled": true,
      "allowedClasses": ["example:first", "other:second"],
      "activeClass": "example:first"
    }
  },
  "addonEnabled": {
    "example:addon": false,
    "other:addon": true
  }
}
```

`activeClass` is a stored selection, including while the player is class-banned
or the addon is disabled. Operational authorization checks the ban and enabled
flags at runtime. Banning a player therefore preserves the selection for unban.
The active ID must belong to `allowedClasses`. Only hybrid players retain more
than one allowed class. An absent addon flag defaults to enabled for newly
registered addons; an explicitly stored `false` survives removal/reinstallation.

Unregistered, syntactically valid historical class/addon IDs remain in state.
Removing an addon does not erase its authorization or runtime enabled flag.
Unknown IDs cannot grant operational authorization until a matching class/addon
is registered again. Sorting keys/sets makes snapshots deterministic.

## Invalid data and recovery

Load diagnoses invalid individual player UUIDs, records, IDs, and field types and
recovers other valid records. Invalid class IDs are skipped; an active ID outside
the recovered allowed set is cleared. A non-hybrid record with multiple allowed
IDs retains its authorized active ID, or the lexicographically first allowed ID
when it has no active selection. Invalid boolean values use safe defaults:
`classBanned=true`, `hybridEnabled=false`, and addon enabled state `false`. A saved
player record missing its `classBanned` field is also treated as class-banned,
preventing damaged data from reviving abilities. A player without any saved
record keeps the normal unbanned, non-hybrid, unauthorized initial state.

Malformed JSON, invalid UTF-8, duplicate JSON keys, excessive nesting, invalid
schema headers, missing/malformed `players` or `addonEnabled` collections, and
unsupported schema versions protect the primary file from writes. These failures
must never produce a writable empty state that silently re-enables disabled
addons. Schema versions newer than supported fail immediately, even when an
older backup exists, preventing accidental downgrade.

If a corrupt or missing primary has a valid `core-state.json.bak`, load may
recover that snapshot in read-only mode. Saving remains blocked, and the primary
and backup stay unchanged. Without a usable backup, load fails. The server
integration logs the failure and refuses to initialize that world state.

To recover, stop the server, keep copies of both files, review the diagnostic and
backup, then restore a reviewed schema-1 snapshot to `core-state.json`. Start a
new server/store afterward. Never overwrite a newer schema with an older backup;
use a Core release supporting that schema. The store reads at most 16 MiB.

## Durable saves

Saving validates that the primary still matches the file loaded or last saved by
the store. External edits cause a read-only refusal instead of overwriting those
edits. The store also validates existing state before a first save when callers
have not explicitly loaded it.

Any I/O failure during save blocks subsequent writes for that store. Repair the
storage problem and reload a reviewed snapshot before enabling further changes.
The server saves a proposed authorization snapshot before committing it in
memory or invoking lifecycle callbacks. A failed save therefore leaves the live
authorization and revision unchanged. A failure during the final directory
flush can occur after the new primary was renamed; the disk may already contain
the proposed snapshot. Review the primary and backup before restarting after
any storage failure.

New JSON is written to a temporary file in the same directory and forced to
storage. The previous primary is copied verbatim to another forced temporary
file and atomically replaces `core-state.json.bak`. This preserves the original
JSON, including invalid individual fields, when a partially recovered state is
normalized. The new primary then atomically replaces `core-state.json`. Directory
metadata is forced after each rename. A filesystem without atomic rename support
fails the save while leaving the prior primary in place. Temporary files are
removed after failures when possible. The backup records the preceding successful
primary; it is not a substitute for an operator's regular world backups.
Windows Java NIO cannot open directories for metadata flushing. On Windows only,
the store emits one diagnostic and skips directory fsync; temporary files are
still forced and both replacements must still be atomic. Power-loss durability
of directory entries is therefore weaker on Windows. On other platforms,
directory flush failures remain fatal and put the store into read-only mode.

Neither class switching, hybrid normalization, class banning, runtime disabling,
nor persistence recovery touches addon-owned persistent data. Addon lifecycle
callbacks should clear transient effects while leaving progression untouched.
