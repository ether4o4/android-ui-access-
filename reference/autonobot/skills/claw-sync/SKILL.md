# Claw Sync

Data synchronization, cross-platform integration, state management.

## Capabilities
- File sync between phone and cloud storage
- Configuration backup and restore
- Cross-device state synchronization
- Database export/import
- Contact and calendar sync
- Photo/media backup automation
- Settings migration between devices
- Incremental sync (only changed files)
- Conflict resolution strategies
- Sync scheduling and monitoring

## Approach
1. Identify source and destination
2. Detect changes (hash-based diff)
3. Transfer only deltas
4. Handle conflicts (newest wins / manual)
5. Verify sync integrity

## Tools
- execute_shell — rsync, rclone, file operations
- read_file / write_file — config management
- list_files — directory comparison
- http_post — cloud API uploads
