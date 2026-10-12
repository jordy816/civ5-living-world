Civ V Living World - the server
===============================
Civilization, played inside Minecraft, for you and your friends.

Needs: Java 25 (https://adoptium.net) and about 4 GB of memory to spare.

1. Read eula.txt and set eula=true there (you accept Minecraft's EULA: https://aka.ms/MinecraftEULA).
2. Look at the first lines of server.properties: port 25565, accounts checked with Mojang,
   and only who is on the white list comes in.
3. Start it: ./start.sh (Linux, macOS) or start.bat (Windows). The first start downloads
   Fabric's server launcher and Minecraft's own server and makes the world.
4. In the server's own window:
       whitelist add YourName
       whitelist add FriendsName
       op YourName
5. Your friends need the same version of the mod: CivLivingWorld-<version>-prism.zip from the
   same release. Give them your address and port.

The operator who joins first finds the table and sets the game up. The game is kept in the
world folder (world/livingworld-civ.json); back that folder up and the game is backed up.

Updating: stop the server, replace mods/civ5-living-world-<old>.jar by the newer jar, start it.

All of this at length, and what to do when something is wrong: docs/INSTALL.md at
https://github.com/jordy816/civ5-living-world
