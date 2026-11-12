# Contributing

## Ground rules

1. **Never** commit simulation files, case folders, generated reports or exported results. See `.gitignore`.
2. **Never** commit licence files, licence server addresses or credentials.
3. Keep the inner layout of `_SIMKIT/`: every macro finds the kit root from its own location.
4. Files under `_SIMKIT/` are stored byte-for-byte (no line-ending conversion, see `.gitattributes`):
   the macros and the batch runners read them as they are.
5. A change to a tool must keep the kit's conventions (`SimKit.java` header): release-dependent STAR-CCM+ calls in
   try/catch, writes confined to the case folders, nothing ever deleted.

## Branching

- `main`: released kit only, tagged `v<version>` at each release.
- `dev`: integration base; topic branches start from `dev` and merge back into it.
- Topic branches: `feat/<topic>`, `fix/<topic>`, `docs/<topic>`.

## Before a pull request

- Update `_SIMKIT/VERSION.txt` when the kit changes.
- Run the batch validation (`_SIMKIT/TESTING.txt`, section A) on at least one STAR-CCM+ release and paste the
  `PASTE_THIS.txt` summary in the pull request.
- Run the GUI test steps that cover what you changed (`_SIMKIT/TESTING.txt`, section B).
