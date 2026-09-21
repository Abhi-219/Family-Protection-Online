# Agent Workspace Rules

## Strict temporary-file policy

All temporary files created by the agent, its tools, scripts, tests, or diagnostic workflows MUST be created only inside the workspace directory:

    TempFiles/

This is a strict workspace rule:

- Never create temporary or scratch files in the operating-system temp directory, user profile directories, project root, `app/`, `build/`, `.gradle/`, `.idea/`, or any other location.
- Before creating a temporary file, ensure its path is under `TempFiles/`.
- Use workspace-relative paths such as `TempFiles/<name>` whenever possible.
- Keep generated logs, patches, exports, intermediate data, test artifacts, and one-off scripts in `TempFiles/`.



Before every file-creation operation, verify that the destination is inside `TempFiles/`. This requirement takes precedence over convenience and applies to all agent actions in this workspace.  
    

####

#### All the temp files  / copilot, agent  analysis  must be  created  inside Folder : TempFiles/