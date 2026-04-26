# Security Policies

Command safety is enforced before process execution.

Modes:

- `WARN`: allow unknown binary and log warning.
- `BLOCK`: reject unknown binary.
- `ALLOW`: skip safe-registry checks.

Safe registry defaults include common package-manager executables and is configurable.
