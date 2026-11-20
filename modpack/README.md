# GTSH pack overrides

These files are distribution overrides, not scripts inside the standalone GTSH JAR.
Copy them to the `overrides/` directory of the GTL Enhanced 3.9.3 Modrinth
pack (or replace the corresponding paths in an installed instance).
Do not add a second copy of `armor_abilities.js`: replace the existing one.
The existing pack's `player_helpers.js`, client key code and key registration remain required.
On a dedicated server, update the server-side script and reload the pack or restart the server.

`node --test modpack/tests/armor-flight.test.cjs` exercises the packaged
handler against mocked Forge/KubeJS players. This does not replace game testing.
The companion `RhinoFlightSmoke.java` runs the patched handler with the packed
KubeJS Rhino engine and a Java armor iterator. Give its source launcher the
Rhino and logging dependencies on the classpath, and pass the script path.
Neither check replaces in-game testing.

The armor script is derived from the existing GTL Enhanced 3.9.3 + GTSH 4.9.4
pack override (unchanged between the original and protocol 23 archives).
This repository's license applies to this fix; upstream modpack components
are not redistributed here.
