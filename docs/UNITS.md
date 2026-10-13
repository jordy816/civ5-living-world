# The units, and who they are in Minecraft

Every unit of the game: what it is in numbers, what it takes, and which of Minecraft's creatures stands
for it on the land. The numbers are the game's own rules (Civilization V with Gods & Kings as
[Unciv](https://github.com/yairm210/Unciv) has them, and the Shoshone's two units); the creature is the
mod's choice. This page is written from both by `scripts/dev/units-page.sh`, so it says what the game
does.

**How a creature is chosen.** By what the unit is and how strong, not by the age it is from: it should
be told apart at a glance, and the stronger should look the stronger.

| What the unit is | The creature |
|---|---|
| Fights hand to hand | Zombie, then husk, then stronger undead and piglins as the strength goes up; spearmen and pikemen are drowned with a trident |
| Shoots | Skeleton, stray, bogged, then a piglin with a crossbow |
| Rides | A rider on a zombie horse, a horse or a skeleton horse; on a camel; an archer on a ravager for elephants |
| Scouts | A wolf |
| Siege | Creepers for catapults and trebuchets, a blaze for cannons and guns, a ghast for artillery and rockets |
| Armour | A ravager |
| In the air | A vex for fighters, a happy ghast for bombers, a bee for the helicopter |
| At sea | A boat with a crew: the stronger the ship, the darker its wood and the harder its crew |
| Peaceful work | Villagers by trade (a mason for the Worker, a librarian for the Great Scientist), a wandering trader for the Settler |

A unit stands **the larger the stronger it is** (from nine tenths to one and a quarter of its kind's own
size, and several times life-size in all, so that it can be seen from above), and wears its owner's
colour where it can wear anything; its helmet says the age of its people (leather, chain, iron, diamond,
netherite). **Barbarians are illagers** whatever the unit: a vindicator with an axe hand to hand, a
pillager with a crossbow for those who shoot. **Anybody crossing water** sits in a boat.

**On foot** (F12) the soldiers that an enemy unit sends at you are the same creature, life-size and with
a will of their own. Where that creature cannot fight on the ground a stand-in comes: a husk for a
creeper, a vindicator for an evoker, an iron golem for a warden, and a zombie or (for units that shoot)
a skeleton for everything that flies or floats. How hard they hit and how much they take follows the
unit's strength: see [GAMEPLAY.md](GAMEPLAY.md#walking-the-land).

Strength is what the unit fights with hand to hand and defends with; *Ranged* is what it shoots with and
how many hexes far; *Moves* is hexes a turn on open land; *Cost* is Production at normal speed (a Quick
game takes two thirds). *Whose* names the one civilization that has it, and what it stands in for.

## Without a technology

| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |
|---|---|---|---|---|---|---|---|
| **Great Admiral** | Drowned with a trident, in a boat |  |  | 4 |  |  |  |
| **Great Artist** | Villager (cartographer) |  |  | 2 |  |  |  |
| **Great Engineer** | Villager (toolsmith) |  |  | 2 |  |  |  |
| **Great General** | Wither skeleton with a golden sword, on a horse |  |  | 2 |  |  |  |
| **Great Merchant** | Wandering trader |  |  | 2 |  |  |  |
| **Great Prophet** | Evoker |  |  | 2 |  |  |  |
| **Great Scientist** | Villager (librarian) |  |  | 2 |  |  |  |
| **Inquisitor** | Witch |  |  | 3 |  |  |  |
| **Missionary** | Villager (cleric) |  |  | 4 |  |  |  |
| **Brute** | Zombie with a stone axe | 8 |  | 2 | 20 |  | Barbarians (their Warrior) |
| **Galley** | Drowned with a trident, in a boat | 7 |  | 3 | 40 |  | Barbarians |
| **Khan** | Wither skeleton with a golden sword, on a horse |  |  | 5 |  |  | Mongolia (their Great General) |

## Ancient era

| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |
|---|---|---|---|---|---|---|---|
| **Scout** | Wolf | 5 |  | 2 | 25 | Agriculture |  |
| **Settler** | Wandering trader |  |  | 2 | 106 | Agriculture |  |
| **Warrior** | Zombie with a stone axe | 8 |  | 2 | 40 | Agriculture |  |
| **Worker** | Villager (mason) |  |  | 2 | 70 | Agriculture |  |
| **Atlatlist** | Skeleton with a bow | 5 | 7 (range 2) | 2 | 36 | Agriculture | The Maya (their Archer) |
| **Jaguar** | Zombie with a stone axe | 8 |  | 2 | 40 | Agriculture | Aztecs (their Warrior) |
| **Maori Warrior** | Zombie with a stone axe | 8 |  | 2 | 40 | Agriculture | Polynesia (their Warrior) |
| **Pathfinder** | Wolf | 8 |  | 2 | 45 | Agriculture | The Shoshone (their Scout) |
| **Archer** | Skeleton with a bow | 5 | 7 (range 2) | 2 | 40 | Archery |  |
| **Bowman** | Stray with a bow | 7 | 9 (range 2) | 2 | 40 | Archery | Babylon (their Archer) |
| **Skirmisher** | Skeleton with a bow | 4 | 7 (range 2) | 2 | 40 | Archery | Barbarians (their Archer) |
| **Slinger** | Skeleton with a bow | 4 | 7 (range 2) | 2 | 40 | Archery | Inca (their Archer) |
| **Chariot Archer** | Skeleton with a bow, on a skeleton horse | 6 | 10 (range 2) | 4 | 56 | The Wheel |  |
| **Spearman** | Drowned with a trident | 11 |  | 2 | 56 | Bronze Working |  |
| **Trireme** | Drowned with a trident, in a boat | 10 |  | 4 | 45 | Sailing |  |
| **Work Boats** | Villager (fisherman), in a boat |  |  | 4 | 30 | Sailing |  |
| **Battering Ram** | Husk with an iron sword | 10 |  | 2 | 75 | Bronze Working | The Huns (their Spearman) |
| **Dromon** | Drowned with a trident, in a boat | 8 | 10 (range 2) | 4 | 56 | Sailing | Byzantium (their Trireme) |
| **Hoplite** | Drowned with a trident | 13 |  | 2 | 56 | Bronze Working | Greece (their Spearman) |
| **Horse Archer** | Skeleton with a bow, on a skeleton horse | 7 | 10 (range 2) | 4 | 56 | The Wheel | The Huns (their Chariot Archer) |
| **Marauder** | Husk with an iron sword | 10 |  | 2 | 56 | Bronze Working | Barbarians (their Spearman) |
| **Persian Immortal** | Drowned with a trident | 12 |  | 2 | 56 | Bronze Working | Persia (their Spearman) |
| **Pictish Warrior** | Husk with an iron sword | 11 |  | 2 | 56 | Bronze Working | Celts (their Spearman) |
| **Quinquereme** | Drowned with a trident, in a boat | 13 |  | 4 | 45 | Sailing | Carthage (their Trireme) |
| **War Chariot** | Skeleton with a bow, on a skeleton horse | 6 | 10 (range 2) | 5 | 56 | The Wheel | Egypt (their Chariot Archer) |
| **War Elephant** | Skeleton with a bow, on a ravager | 9 | 11 (range 2) | 3 | 70 | The Wheel | India (their Chariot Archer) |

## Classical era

| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |
|---|---|---|---|---|---|---|---|
| **Catapult** | Creeper | 7 | 8 (range 2) | 2 | 75 | Mathematics |  |
| **Composite Bowman** | Stray with a bow | 7 | 11 (range 2) | 2 | 75 | Construction |  |
| **Horseman** | Zombie with an iron sword, on a zombie horse | 12 |  | 4 | 75 | Horseback Riding |  |
| **African Forest Elephant** | Skeleton with a bow, on a ravager | 14 |  | 3 | 100 | Horseback Riding | Carthage (their Horseman) |
| **Ballista** | Creeper | 8 | 10 (range 2) | 2 | 75 | Mathematics | Rome (their Catapult) |
| **Cataphract** | Wither skeleton with an iron sword, on a horse | 15 |  | 3 | 75 | Horseback Riding | Byzantium (their Horseman) |
| **Companion Cavalry** | Zombie with an iron sword, on a zombie horse | 14 |  | 5 | 75 | Horseback Riding | Greece (their Horseman) |
| **Swordsman** | Husk with an iron sword | 14 |  | 2 | 75 | Iron Working |  |
| **Legion** | Wither skeleton with an iron sword | 17 |  | 2 | 75 | Iron Working | Rome (their Swordsman) |
| **Mohawk Warrior** | Husk with an iron sword | 14 |  | 2 | 75 | Iron Working | Iroquois (their Swordsman) |

## Medieval era

| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |
|---|---|---|---|---|---|---|---|
| **Pikeman** | Drowned with a trident | 16 |  | 2 | 90 | Civil Service |  |
| **Berserker** | Wither skeleton with an iron sword | 21 |  | 3 | 120 | Metal Casting | Denmark (their Longswordsman) |
| **Landsknecht** | Drowned with a trident | 16 |  | 2 | 45 | Civil Service | Germany (their Pikeman) |
| **Crossbowman** | Bogged with a bow | 13 | 18 (range 2) | 2 | 120 | Machinery |  |
| **Galleass** | Drowned with a trident, in a boat | 16 | 17 (range 2) | 3 | 100 | Compass |  |
| **Knight** | Wither skeleton with an iron sword, on a horse | 20 |  | 4 | 120 | Chivalry |  |
| **Longswordsman** | Wither skeleton with an iron sword | 21 |  | 2 | 120 | Steel |  |
| **Trebuchet** | Charged creeper | 12 | 14 (range 2) | 2 | 120 | Physics |  |
| **Camel Archer** | Skeleton with a bow, on a camel | 17 | 21 (range 2) | 4 | 120 | Chivalry | Arabia (their Knight) |
| **Chu-Ko-Nu** | Bogged with a bow | 13 | 14 (range 2) | 2 | 120 | Machinery | China (their Crossbowman) |
| **Conquistador** | Wither skeleton with an iron sword, on a horse | 20 |  | 4 | 135 | Chivalry | Spain (their Knight) |
| **Hwach'a** | Piglin with a crossbow | 11 | 26 (range 2) | 2 | 120 | Physics | Korea (their Trebuchet) |
| **Keshik** | Skeleton with a bow, on a skeleton horse | 15 | 16 (range 2) | 5 | 120 | Chivalry | Mongolia (their Knight) |
| **Longbowman** | Bogged with a bow | 13 | 18 (range 2) | 2 | 120 | Machinery | England (their Crossbowman) |
| **Mandekalu Cavalry** | Wither skeleton with an iron sword, on a horse | 20 |  | 4 | 110 | Chivalry | Songhai (their Knight) |
| **Naresuan's Elephant** | Skeleton with a bow, on a ravager | 25 |  | 3 | 120 | Chivalry | Siam (their Knight) |
| **Samurai** | Wither skeleton with an iron sword | 21 |  | 2 | 120 | Steel | Japan (their Longswordsman) |

## Renaissance era

| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |
|---|---|---|---|---|---|---|---|
| **Caravel** | Drowned with a trident, in a boat | 20 |  | 4 | 120 | Astronomy |  |
| **Musketman** | Piglin with a crossbow | 24 |  | 2 | 150 | Gunpowder |  |
| **Janissary** | Piglin with a crossbow | 24 |  | 2 | 150 | Gunpowder | The Ottomans (their Musketman) |
| **Minuteman** | Piglin with a crossbow | 24 |  | 2 | 150 | Gunpowder | America (their Musketman) |
| **Musketeer** | Piglin with a crossbow | 28 |  | 2 | 150 | Gunpowder | France (their Musketman) |
| **Tercio** | Piglin with a crossbow | 26 |  | 2 | 160 | Gunpowder | Spain (their Musketman) |
| **Turtle Ship** | Piglin with a crossbow, in a spruce boat | 36 |  | 4 | 120 | Astronomy | Korea (their Caravel) |
| **Cannon** | Blaze | 14 | 20 (range 2) | 2 | 185 | Chemistry |  |
| **Frigate** | Piglin with a crossbow, in a spruce boat | 25 | 28 (range 2) | 5 | 185 | Navigation |  |
| **Lancer** | Wither skeleton with an iron sword, on a horse | 25 |  | 4 | 185 | Metallurgy |  |
| **Privateer** | Piglin with a crossbow, in a spruce boat | 25 |  | 5 | 150 | Navigation |  |
| **Hakkapeliitta** | Piglin with a crossbow | 25 |  | 4 | 185 | Metallurgy | Sweden (their Lancer) |
| **Sea Beggar** | Piglin with a crossbow, in a spruce boat | 25 |  | 5 | 150 | Navigation | The Netherlands (their Privateer) |
| **Ship of the Line** | Piglin with a crossbow, in a spruce boat | 30 | 35 (range 2) | 5 | 185 | Navigation | England (their Frigate) |
| **Sipahi** | Wither skeleton with an iron sword, on a horse | 25 |  | 5 | 185 | Metallurgy | The Ottomans (their Lancer) |

## Industrial era

| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |
|---|---|---|---|---|---|---|---|
| **Cavalry** | Piglin brute with a golden sword, on a horse | 34 |  | 4 | 225 | Military Science |  |
| **Gatling Gun** | Blaze | 30 | 30 (range 1) | 2 | 225 | Industrialization |  |
| **Rifleman** | Piglin brute with a crossbow | 34 |  | 2 | 225 | Rifling |  |
| **Carolean** | Piglin brute with a crossbow | 34 |  | 2 | 225 | Rifling | Sweden (their Rifleman) |
| **Comanche Riders** | Piglin brute with a golden sword, on a horse | 34 |  | 5 | 200 | Military Science | The Shoshone (their Cavalry) |
| **Cossack** | Piglin brute with a golden sword, on a horse | 34 |  | 4 | 225 | Military Science | Russia (their Cavalry) |
| **Hussar** | Piglin brute with a golden sword, on a horse | 34 |  | 5 | 225 | Military Science | Austria (their Cavalry) |
| **Mehal Sefari** | Piglin brute with a crossbow | 34 |  | 2 | 200 | Rifling | Ethiopia (their Rifleman) |
| **Norwegian Ski Infantry** | Piglin brute with a crossbow | 34 |  | 2 | 225 | Rifling | Denmark (their Rifleman) |
| **Artillery** | Ghast | 21 | 28 (range 3) | 2 | 320 | Dynamite |  |
| **Ironclad** | Wither skeleton with a crossbow, in a dark oak boat | 45 |  | 3 | 250 | Steam Power |  |

## Modern era

| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |
|---|---|---|---|---|---|---|---|
| **Great War Bomber** | Happy ghast | 50 | 50 (range 6) |  | 325 | Flight |  |
| **Great War Infantry** | Iron golem | 50 |  | 2 | 320 | Replaceable Parts |  |
| **Submarine** | Wither skeleton with a crossbow, in a pale oak boat | 35 | 60 (range 2) | 5 | 325 | Refrigeration |  |
| **Triplane** | Vex with an iron sword | 35 | 35 (range 5) |  | 325 | Flight |  |
| **Foreign Legion** | Iron golem | 50 |  | 2 | 320 | Replaceable Parts | France (their Great War Infantry) |
| **Anti-Aircraft Gun** | Shulker | 50 |  | 2 | 375 | Ballistics |  |
| **Battleship** | Wither skeleton with a crossbow, in a dark oak boat | 55 | 55 (range 3) | 5 | 375 | Electronics |  |
| **Carrier** | Piglin with a crossbow, in a spruce boat | 40 |  | 5 | 375 | Electronics |  |
| **Destroyer** | Wither skeleton with a crossbow, in a dark oak boat | 55 |  | 6 | 375 | Combustion |  |
| **Infantry** | Iron golem | 70 |  | 2 | 375 | Plastics |  |
| **Landship** | Ravager | 60 |  | 4 | 350 | Combustion |  |
| **Machine Gun** | Blaze | 60 | 60 (range 1) | 2 | 350 | Ballistics |  |

## Atomic era

| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |
|---|---|---|---|---|---|---|---|
| **Anti-Tank Gun** | Ravager | 50 |  | 2 | 300 | Combined Arms |  |
| **Bomber** | Happy ghast | 65 | 65 (range 10) |  | 375 | Radar |  |
| **Fighter** | Vex with an iron sword | 45 | 45 (range 8) |  | 375 | Radar |  |
| **Marine** | Iron golem | 65 |  | 2 | 400 | Pharmaceuticals |  |
| **Paratrooper** | Iron golem | 65 |  | 2 | 375 | Radar |  |
| **Tank** | Ravager | 70 |  | 5 | 375 | Combined Arms |  |
| **B17** | Happy ghast | 70 | 70 (range 10) |  | 375 | Radar | America (their Bomber) |
| **Panzer** | Ravager | 80 |  | 6 | 375 | Combined Arms | Germany (their Tank) |
| **Zero** | Vex with an iron sword | 45 | 45 (range 8) |  | 375 | Radar | Japan (their Fighter) |
| **Atomic Bomb** | Charged creeper | 150 | 150 (range 10) |  | 600 | Nuclear Fission |  |
| **Helicopter Gunship** | Bee | 60 |  | 6 | 425 | Computers |  |
| **Mobile SAM** | Shulker | 65 |  | 3 | 425 | Rocketry |  |
| **Rocket Artillery** | Ghast | 45 | 60 (range 3) | 2 | 425 | Rocketry |  |

## Information era

| Unit | In Minecraft | Strength | Ranged | Moves | Cost | Needs | Whose |
|---|---|---|---|---|---|---|---|
| **Guided Missile** | Blaze | 60 | 60 (range 8) |  | 150 | Advanced Ballistics |  |
| **Jet Fighter** | Vex with an iron sword | 75 | 75 (range 10) |  | 425 | Lasers |  |
| **Mechanized Infantry** | Ravager | 90 |  | 3 | 375 | Mobile Tactics |  |
| **Missile Cruiser** | Piglin brute with a crossbow, in a mangrove boat | 80 | 100 (range 3) | 7 | 425 | Robotics |  |
| **Modern Armor** | Ravager | 100 |  | 5 | 425 | Lasers |  |
| **Nuclear Missile** | Charged creeper | 300 | 300 (range 12) |  | 1000 | Advanced Ballistics |  |
| **Nuclear Submarine** | Piglin brute with a crossbow, in a pale oak boat | 50 | 85 (range 3) | 6 | 425 | Telecommunications |  |
| **SS Booster** | Villager (nitwit) |  |  | 2 | 750 | Advanced Ballistics |  |
| **SS Cockpit** | Villager (nitwit) |  |  | 2 | 750 | Satellites |  |
| **Giant Death Robot** | Warden | 150 |  | 5 | 425 | Nuclear Fusion |  |
| **SS Engine** | Villager (nitwit) |  |  | 2 | 750 | Particle Physics |  |
| **SS Stasis Chamber** | Villager (nitwit) |  |  | 2 | 750 | Nanotechnology |  |
| **Stealth Bomber** | Happy ghast | 85 | 85 (range 20) |  | 425 | Stealth |  |
