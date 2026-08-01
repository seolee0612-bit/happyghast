# Happy Ghast Chunk Loader

A server-side Fabric mod for Minecraft **1.21.1** that lets administrators register specific Happy Ghasts and keep only their current chunk plus one forward chunk loaded.

The mod does not depend directly on Vanilla Backport. It recognizes any entity whose registry path is `happy_ghast`.

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16+
- Fabric API
- Java 21

## Commands

Operator permission level 2 is required.

```mcfunction
/happyghast initialize "Metro 1"
/happyghast list
/happyghast remove <uuid>
/happyghast clear
```

`initialize` searches within 64 blocks in the command executor's current dimension. The custom name is only used during registration; the ghast is tracked by UUID afterward.

## Behavior

- Keeps the registered ghast's current chunk entity-ticking.
- Loads one additional chunk in the dominant horizontal movement direction.
- Tracks multiple ghasts independently.
- Saves UUID, dimension, and last-known chunk to `config/happy-ghast-chunk-loader.json`.
- On restart, temporarily loads the last-known chunk and attempts to reacquire the entity.
- Releases tickets after 10 seconds if the entity cannot be found, preventing an abandoned chunk from remaining loaded forever.

## Build

The included GitHub Actions workflow builds automatically. Locally, use Gradle 8.10.2:

```bash
gradle clean build
```

The production JAR appears in `build/libs/`.

## First test checklist

1. Install the built JAR and Fabric API on the server.
2. Give one Happy Ghast a unique name.
3. Stand within 64 blocks and run `/happyghast initialize "Name"`.
4. Run `/happyghast list` and confirm the status becomes `active`.
5. Send the ghast across the simulation-distance boundary and observe whether movement continues.
6. Check server MSPT before and after registration.

## Current design limits

- The forward chunk uses the dominant X or Z velocity axis, so exactly two chunks are held at most per ghast.
- A stationary ghast holds only its current chunk.
- This initial version does not selectively disable random ticks, mob spawning, or other entities in the loaded chunks.
- Vanilla Backport updates may change the entity registry path; confirm it remains `happy_ghast` if detection fails.

## License

MIT
