-- A small stand-in for Civilization V's Lua API, enough to run livingworld.lua in tests.
-- The world lives in the global table F so tests can read and change it.

F = { W = FAKE_W or 8, H = FAKE_H or 6, turn = 5, active = 0, turnActive = true, multiplayer = false,
	lookAt = {}, selected = nil, moves = {}, dirty = 0, icons = {}, built = 0, released = 0 }

local function typeTable(rows)
	local t = {}
	for _, row in ipairs(rows) do
		if row.ID ~= nil then t[row.ID] = row end
		if row.Type ~= nil then t[row.Type] = row end
	end
	return setmetatable(t, { __call = function()
		local i = 0
		return function()
			i = i + 1
			return rows[i]
		end
	end })
end

GameInfo = {
	Terrains = typeTable({
		{ ID = 0, Type = "TERRAIN_GRASS", Description = "Grassland" }, { ID = 1, Type = "TERRAIN_PLAINS", Description = "Plains" },
		{ ID = 5, Type = "TERRAIN_COAST", Description = "Coast" }, { ID = 6, Type = "TERRAIN_OCEAN", Description = "Ocean" } }),
	Features = typeTable({ { ID = 5, Type = "FEATURE_FOREST", Description = "Forest" } }),
	Improvements = typeTable({ { ID = 3, Type = "IMPROVEMENT_FARM", Description = "Farm" },
		{ ID = 7, Type = "IMPROVEMENT_ACADEMY", Description = "Academy", CreatedByGreatPerson = true },
		{ ID = 8, Type = "IMPROVEMENT_MOAI", Description = "Moai", SpecificCivRequired = true, CivilizationType = "CIVILIZATION_POLYNESIA" } }),
	Resources = typeTable({ { ID = 1, Type = "RESOURCE_HORSE", Description = "Horses" } }),
	Routes = typeTable({ { ID = 0, Type = "ROUTE_ROAD", Description = "Road" } }),
	Units = typeTable({ { ID = 10, Type = "UNIT_WARRIOR" }, { ID = 11, Type = "UNIT_WORKER" } }),
	Buildings = typeTable({ { ID = 2, Type = "BUILDING_MONUMENT", Description = "Monument", BuildingClass = "BUILDINGCLASS_MONUMENT" },
		{ ID = 3, Type = "BUILDING_PYRAMID", Description = "Pyramids", BuildingClass = "BUILDINGCLASS_PYRAMID" },
		{ ID = 4, Type = "BUILDING_GRANARY", Description = "Granary", BuildingClass = "BUILDINGCLASS_GRANARY" } }),
	BuildingClasses = typeTable({ { ID = 0, Type = "BUILDINGCLASS_MONUMENT", MaxGlobalInstances = -1 },
		{ ID = 1, Type = "BUILDINGCLASS_PYRAMID", MaxGlobalInstances = 1 }, { ID = 2, Type = "BUILDINGCLASS_GRANARY", MaxGlobalInstances = -1 } }),
	Eras = typeTable({ { ID = 0, Type = "ERA_ANCIENT" }, { ID = 1, Type = "ERA_CLASSICAL" } }),
	Technologies = typeTable({ { ID = 0, Type = "TECH_AGRICULTURE", Description = "Agriculture" }, { ID = 1, Type = "TECH_POTTERY", Description = "Pottery" },
		{ ID = 2, Type = "TECH_BRONZE_WORKING", Description = "Bronze Working" }, { ID = 3, Type = "TECH_THE_WHEEL", Description = "The Wheel" } }),
	Builds = typeTable({ { ID = 0, Type = "BUILD_FARM", Description = "Construct a [LINK=IMPROVEMENT_FARM]Farm[\\LINK]", ImprovementType = "IMPROVEMENT_FARM" },
		{ ID = 1, Type = "BUILD_ROAD", Description = "Road", RouteType = "ROUTE_ROAD", PrereqTech = "TECH_THE_WHEEL" },
		{ ID = 2, Type = "BUILD_REMOVE_FOREST", Description = "Remove Forest", PrereqTech = "TECH_BRONZE_WORKING" },
		{ ID = 3, Type = "BUILD_REPAIR", Description = "Repair", Repair = true },
		{ ID = 4, Type = "BUILD_ACADEMY", Description = "Academy", ImprovementType = "IMPROVEMENT_ACADEMY" },
		{ ID = 5, Type = "BUILD_MOAI", Description = "Moai", ImprovementType = "IMPROVEMENT_MOAI" } }),
	Unit_Builds = typeTable({ { UnitType = "UNIT_WORKER", BuildType = "BUILD_FARM" }, { UnitType = "UNIT_WORKER", BuildType = "BUILD_ROAD" },
		{ UnitType = "UNIT_WORKER", BuildType = "BUILD_REMOVE_FOREST" }, { UnitType = "UNIT_WORKER", BuildType = "BUILD_REPAIR" },
		{ UnitType = "UNIT_WORKER", BuildType = "BUILD_MOAI" }, { UnitType = "UNIT_SCIENTIST", BuildType = "BUILD_ACADEMY" } }),
	BuildFeatures = typeTable({ { BuildType = "BUILD_REMOVE_FOREST", FeatureType = "FEATURE_FOREST", PrereqTech = "TECH_BRONZE_WORKING", Production = 20, Remove = true },
		{ BuildType = "BUILD_FARM", FeatureType = "FEATURE_FOREST", PrereqTech = "TECH_BRONZE_WORKING", Remove = true } }),
	MinorCivTraits = typeTable({ { ID = 0, Type = "MINOR_TRAIT_CULTURED" } }),
	PolicyBranchTypes = typeTable({ { ID = 0, Type = "POLICY_BRANCH_TRADITION", Description = "Tradition" }, { ID = 1, Type = "POLICY_BRANCH_LIBERTY", Description = "Liberty" } }),
	Policies = typeTable({ { ID = 3, Type = "POLICY_ARISTOCRACY", Description = "Aristocracy", PolicyBranchType = "POLICY_BRANCH_TRADITION" },
		{ ID = 9, Type = "POLICY_CITIZENSHIP", Description = "Citizenship", PolicyBranchType = "POLICY_BRANCH_LIBERTY" } }),
	Projects = typeTable({}),
	Processes = typeTable({}),
	Domains = typeTable({ { ID = 0, Type = "DOMAIN_SEA" }, { ID = 2, Type = "DOMAIN_LAND" } }),
	Leaders = typeTable({ { ID = 7, Type = "LEADER_AUGUSTUS", PortraitIndex = 3, IconAtlas = "LEADER_ATLAS" } }),
	Civilizations = typeTable({ { ID = 0, Type = "CIVILIZATION_ROME", ArtStyleType = "ARTSTYLE_GRECO_ROMAN" }, { ID = 1, Type = "CIVILIZATION_EGYPT" },
		{ ID = 9, Type = "CIVILIZATION_BARBARIAN" } }),
}
GameDefines = { MAX_PLAYERS = 64, MINOR_GOLD_GIFT_SMALL = 250, MINOR_GOLD_GIFT_MEDIUM = 500, MINOR_GOLD_GIFT_LARGE = 1000 }
OrderTypes = { ORDER_TRAIN = 0, ORDER_CONSTRUCT = 1, ORDER_CREATE = 2, ORDER_MAINTAIN = 3 }
FeatureTypes = { NO_FEATURE = -1 }
MinorCivQuestTypes = { MINOR_CIV_QUEST_ROUTE = 0, MINOR_CIV_QUEST_KILL_CAMP = 1 }
MajorCivApproachTypes = { MAJOR_CIV_APPROACH_WAR = 0, MAJOR_CIV_APPROACH_HOSTILE = 1, MAJOR_CIV_APPROACH_GUARDED = 2, MAJOR_CIV_APPROACH_FRIENDLY = 5 }
F.techs = { [0] = true }
F.research = 1
F.gold = 300
F.era = 0
F.influence = 35
F.orders = {}
F.researchSent = {}
F.gifts = {}
InterfaceDirtyBits = { GameData_DIRTY_BIT = 1, CityInfo_DIRTY_BIT = 2, UnitInfo_DIRTY_BIT = 3 }
Locale = { ConvertTextKey = function(key)
	return key
end }

-- Plots -------------------------------------------------------------------------------------
F.plots = {}
local Plot = {}
Plot.__index = Plot
for y = 0, F.H - 1 do
	for x = 0, F.W - 1 do
		local edge = x == 0 or y == 0 or x == F.W - 1 or y == F.H - 1
		F.plots[y * F.W + x] = setmetatable({ x = x, y = y, terrain = edge and 6 or (x % 2), feature = -1, hills = false,
			mountain = false, owner = -1, improvement = -1, resource = -1, route = -1, revealed = x < F.W - 2, visible = x < 5,
			city = false, pillaged = false, wRiver = false, nwRiver = false, neRiver = false }, Plot)
	end
end
function Plot:GetTerrainType() return self.terrain end
function Plot:GetPlotType() return self.mountain and 0 or (self.hills and 1 or (self.terrain >= 5 and 3 or 2)) end
function Plot:GetFeatureType() return self.feature end
function Plot:IsMountain() return self.mountain end
function Plot:IsHills() return self.hills end
function Plot:IsRevealed(team, debug) return self.revealed end
function Plot:IsVisible(team, debug) return self.visible end
function Plot:IsCity() return self.city end
function Plot:IsWater() return self.terrain >= 5 end
function Plot:IsLake() return false end
function Plot:IsImprovementPillaged() return self.pillaged end
function Plot:IsWOfRiver() return self.wRiver end
function Plot:IsNWOfRiver() return self.nwRiver end
function Plot:IsNEOfRiver() return self.neRiver end
function Plot:GetRevealedOwner(team, debug) return self.owner end
function Plot:GetRevealedImprovementType(team, debug) return self.improvement end
function Plot:GetResourceType(team) return self.resource end
function Plot:GetRevealedRouteType(team, debug) return self.route end
function Plot:GetPlotIndex() return self.y * F.W + self.x end
function Plot:GetOwner() return self.owner end
function Plot:GetImprovementType() return self.improvement end
function Plot:GetRouteType() return self.route end
function Plot:SetRevealed(team, value) self.revealed = value end
function Plot:SetImprovementType(id) self.improvement = id end
function Plot:SetRouteType(id) self.route = id end
function Plot:SetFeatureType(id, variety) self.feature = id end
function Plot:IsCoastalLand() return false end

Map = {
	GetGridSize = function() return F.W, F.H end,
	GetNumPlots = function() return F.W * F.H end,
	GetPlotByIndex = function(i) return F.plots[i] end,
	GetPlot = function(x, y)
		if x < 0 or y < 0 or x >= F.W or y >= F.H then return nil end
		return F.plots[y * F.W + x]
	end,
	IsWrapX = function() return false end,
	-- Steps between two plots of a grid whose odd rows are shifted half a hex east.
	PlotDistance = function(x1, y1, x2, y2)
		local q1 = x1 - (y1 - y1 % 2) / 2
		local q2 = x2 - (y2 - y2 % 2) / 2
		local dq, dr = q1 - q2, y1 - y2
		return (math.abs(dq) + math.abs(dr) + math.abs(dq + dr)) / 2
	end,
}
function GridToWorld(x, y)
	return x * 64 + (y % 2) * 32, y * 55.4256, 7 + x
end

-- Units and cities --------------------------------------------------------------------------
local Unit = {}
Unit.__index = Unit
function Unit:GetOwner() return self.owner end
function Unit:GetID() return self.id end
function Unit:GetUnitType() return self.type end
function Unit:GetName() return self.name end
function Unit:GetX() return self.x end
function Unit:GetY() return self.y end
function Unit:GetPlot() return Map.GetPlot(self.x, self.y) end
function Unit:GetCurrHitPoints() return 100 - self.damage end
function Unit:GetMaxHitPoints() return 100 end
function Unit:GetDamage() return self.damage end
function Unit:SetDamage(v) self.damage = v end
function Unit:MovesLeft() return self.moves end
function Unit:MaxMoves() return 120 end
function Unit:GetBaseCombatStrength() return self.strength end
function Unit:GetDomainType() return 2 end
function Unit:IsEmbarked() return false end
function Unit:IsCombatUnit() return self.strength > 0 end
function Unit:IsInvisible(team, debug) return self.invisible or false end
function Unit:IsDead() return self.dead or false end
function Unit:GetLevel() return 2 end
function Unit:GetExperience() return 17 end
function Unit:GetFortifyTurns() return 0 end
function Unit:IsReadyToMove() return self.idle == true end
function Unit:IsAutomated() return false end
function Unit:IsDelayedDeath() return false end
-- GetBaseRangedCombatStrength, IsGarrisoned and IsGreatPerson are left out on purpose: the
-- script must cope with methods a game version does not have.

local City = {}
City.__index = City
function City:GetOwner() return self.owner end
function City:GetID() return self.id end
function City:GetName() return self.name end
function City:GetX() return self.x end
function City:GetY() return self.y end
function City:Plot() return Map.GetPlot(self.x, self.y) end
function City:GetPopulation() return self.pop end
function City:IsCapital() return true end
function City:IsPuppet() return false end
function City:IsOccupied() return false end
function City:IsRazing() return false end
function City:IsResistance() return false end
function City:GetMaxHitPoints() return 200 end
function City:GetDamage() return 20 end
function City:GetProductionUnit() return self.building and -1 or (self.unitType or 10) end
function City:IsHasBuilding(id) return self.has and self.has[id] or false end
function City:CanTrain(id) return id == 10 or id == 11 end
function City:CanConstruct(id) return id == 4 and not (self.has and self.has[4]) end
function City:CanCreate(id) return false end
function City:CanMaintain(id) return false end
function City:GetUnitProductionTurnsLeft(id) return 4 end
function City:GetBuildingProductionTurnsLeft(id) return 6 end
function City:GetProjectProductionTurnsLeft(id) return 9 end
function City:GetProductionBuilding() return self.building or -1 end
function City:GetProductionProject() return -1 end
function City:GetProductionProcess() return -1 end
function City:GetProductionNameKey()
	if self.building then return GameInfo.Buildings[self.building].Description end
	return self.unitType == 11 and "Worker" or "Warrior"
end
function City:GetProduction() return self.production end
function City:ChangeProduction(n) self.production = self.production + n end
function City:GetProductionNeeded() return 40 end
function City:GetProductionTurnsLeft() return 6 end
function City:GetFood() return 3.5 end
function City:GrowthThreshold() return 15 end
function City:GetStrengthValue() return 950 end

local Player = {}
Player.__index = Player
function Player:IsEverAlive() return true end
function Player:IsAlive() return true end
function Player:GetTeam() return self.team end
function Player:IsMinorCiv() return self.minor or false end
function Player:GetCurrentEra() return self.id == 0 and F.era or 0 end
function Player:GetCurrentResearch() return F.research end
function Player:GetResearchTurnsLeft(tech, overflow) return 7 end
function Player:CanResearch(id) return not F.techs[id] end
function Player:GetNumFreeTechs() return 0 end
function Player:GetGold() return F.gold end
function Player:CalculateGoldRate() return 3 end
function Player:GetScience() return 2 end
function Player:GetExcessHappiness() return 4 end
function Player:GetJONSCulture() return 0 end
function Player:GetTotalJONSCulturePerTurn() return 1 end
function Player:GetNextPolicyCost() return 25 end
function Player:GetNumPolicies() return F.policies end
function Player:GetNumPolicyBranchesUnlocked() return F.branches end
-- As the real game: "can unlock" says yes for a branch that is open already.
function Player:CanUnlockPolicyBranch(id) return F.policyDue and GameInfo.PolicyBranchTypes[id] ~= nil end
function Player:IsPolicyBranchUnlocked(id) return F.openBranches[id] == true end
function Player:HasPolicy(id) return F.adopted[id] == true end
function Player:CanAdoptPolicy(id)
	local row = GameInfo.Policies[id]
	return F.policyDue and row ~= nil and F.openBranches[GameInfo.PolicyBranchTypes[row.PolicyBranchType].ID] == true
end
function Player:GetEndTurnBlockingType()
	if F.policyDue then return EndTurnBlockingTypes.ENDTURN_BLOCKING_POLICY end
	for _, u in ipairs(self.units) do
		if u.idle then return EndTurnBlockingTypes.ENDTURN_BLOCKING_UNITS end
	end
	if not F.canEndTurn then return EndTurnBlockingTypes.ENDTURN_BLOCKING_PRODUCTION end
	return EndTurnBlockingTypes.NO_ENDTURN_BLOCKING_TYPE
end
function Player:GetGoldenAgeTurns() return 0 end
function Player:IsDenouncedPlayer(other) return false end
function Player:IsDoF(other) return false end
function Player:GetApproachTowardsUsGuess(other) return 2 end
function Player:GetMinorCivFriendshipWithMajor(other) return F.influence end
function Player:IsAllies(other) return F.influence >= 60 end
function Player:IsFriends(other) return F.influence >= 30 end
function Player:GetMinorCivTrait() return 0 end
function Player:GetAlly() return -1 end
function Player:IsMinorCivDisplayedQuestForPlayer(other, quest) return quest == 1 end
function Player:IsBarbarian() return self.barbarian or false end
function Player:IsHuman() return self.id == 0 end
function Player:GetPlayerColors() return self.primary, self.secondary end
function Player:GetCivilizationType() return self.civ end
function Player:GetCivilizationShortDescription() return self.name end
function Player:GetCivilizationAdjective() return self.adjective end
function Player:GetName() return self.leader end
function Player:GetLeaderType() return 7 end
function Player:IsTurnActive() return F.turnActive end
local function iterate(list)
	local i = 0
	return function()
		i = i + 1
		return list[i]
	end
end
function Player:Units() return iterate(self.units) end
function Player:Cities() return iterate(self.cities) end
function Player:GetUnitByID(id)
	for _, unit in ipairs(self.units) do
		if unit.id == id then return unit end
	end
	return nil
end
function Player:GetCityByID(id)
	for _, city in ipairs(self.cities) do
		if city.id == id then return city end
	end
	return nil
end

local function player(id, team, civ, name, adjective, leader, primary, secondary)
	return setmetatable({ id = id, team = team, civ = civ, name = name, adjective = adjective, leader = leader,
		primary = primary, secondary = secondary, units = {}, cities = {} }, Player)
end
Players = {
	-- As in the real game: the icon colour (gold) first, the background colour (purple) second.
	[0] = player(0, 0, 0, "Rome", "Roman", "Augustus Caesar", { x = 0.941, y = 0.78, z = 0, w = 1 }, { x = 0.275, y = 0, z = 0.463, w = 1 }),
	[1] = player(1, 1, 1, "Egypt", "Egyptian", "Ramesses II", { x = 1, y = 0.984, z = 0.012, w = 1 }, { x = 0.322, y = 0, z = 0.816, w = 1 }),
	[2] = player(2, 2, 1, "Greece", "Greek", "Alexander", { x = 1, y = 1, z = 1, w = 1 }, { x = 0, y = 0, z = 1, w = 1 }),
	[63] = player(63, 63, 9, "Barbarians", "Barbarian", "", { x = 0.5, y = 0, z = 0, w = 1 }, { x = 0, y = 0, z = 0, w = 1 }),
}
Players[63].barbarian = true
Players[22] = player(22, 22, 1, "Geneva", "Genevan", "", { x = 0.1, y = 0.1, z = 0.1, w = 1 }, { x = 0.9, y = 0.9, z = 0.9, w = 1 })
Players[22].minor = true

function F.addUnit(owner, id, type, name, x, y, strength)
	local unit = setmetatable({ owner = owner, id = id, type = type, name = name, x = x, y = y, damage = 0, moves = 120,
		strength = strength }, Unit)
	table.insert(Players[owner].units, unit)
	return unit
end
function F.addCity(owner, id, name, x, y)
	local city = setmetatable({ owner = owner, id = id, name = name, x = x, y = y, pop = 3, production = 12 }, City)
	table.insert(Players[owner].cities, city)
	Map.GetPlot(x, y).city = true
	Map.GetPlot(x, y).owner = owner
	return city
end
F.rome = F.addCity(0, 8192, "Rome", 2, 2)
F.rome.has = { [2] = true, [3] = true }   -- a Monument and the Pyramids
F.warrior = F.addUnit(0, 8193, 10, "Warrior", 3, 2, 8)
F.worker = F.addUnit(0, 8194, 11, "Wor\tker", 2, 3, 0)
F.enemySeen = F.addUnit(1, 8195, 10, "Warrior", 4, 3, 8)
F.enemyHidden = F.addUnit(1, 8196, 10, "Warrior", F.W - 3, 3, 8)   -- revealed plot, but not visible now
F.thebes = F.addCity(1, 8200, "Thebes", F.W - 1, F.H - 2)          -- on a plot never seen
Map.GetPlot(3, 3).feature = 5
Map.GetPlot(3, 3).hills = true
Map.GetPlot(4, 2).improvement = 3
Map.GetPlot(4, 2).route = 0
Map.GetPlot(3, 1).wRiver = true
Map.GetPlot(3, 1).neRiver = true
Map.GetPlot(1, 3).mountain = true

F.met = { [1] = true, [22] = true }
F.war = {}
Teams = setmetatable({}, { __index = function(_, id)
	return {
		IsHasMet = function(self, other) return F.met[other] or false end,
		IsAtWar = function(self, other) return F.war[other] or false end,
		CanDeclareWar = function(self, other) return other == 1 and not F.war[1] end,
		GetTeamTechs = function(self)
			return { HasTech = function(_, id) return F.techs[id] or false end }
		end,
	}
end })

Game = {
	GetActivePlayer = function() return F.active end,
	GetActiveTeam = function() return 0 end,
	GetGameTurn = function() return F.turn end,
	GetStartYear = function() return -4000 end,
	IsNetworkMultiPlayer = function() return F.multiplayer end,
	SelectionListMove = function(plot, a, b_, c)
		table.insert(F.moves, { unit = F.selected, x = plot.x, y = plot.y })
		if F.selected then
			-- One step east or west toward the goal, as a stand-in for the game's path finding.
			if plot.x > F.selected.x then F.selected.x = F.selected.x + 1 elseif plot.x < F.selected.x then F.selected.x = F.selected.x - 1 end
		end
	end,
}
GameInfoTypes = { CONTROL_ENDTURN = 0 }
F.canEndTurn = true
F.policies, F.branches, F.policyDue, F.openBranches, F.adopted = 0, 0, false, {}, {}
F.news, F.dequeued = false, 0
EndTurnBlockingTypes = { NO_ENDTURN_BLOCKING_TYPE = -1, ENDTURN_BLOCKING_POLICY = 0, ENDTURN_BLOCKING_RESEARCH = 1, ENDTURN_BLOCKING_PRODUCTION = 2,
	ENDTURN_BLOCKING_UNITS = 3 }
MissionTypes = { MISSION_SKIP = 7 }
GameMessageTypes = { GAMEMESSAGE_PUSH_MISSION = 1 }
F.endTurns = 0
function Game.DoControl(control)
	if control == GameInfoTypes.CONTROL_ENDTURN then
		-- As the real game: with a unit still waiting for orders the control does nothing at all.
		for _, u in ipairs(Players[F.active].units) do
			if u.idle then return end
		end
		F.endTurns = F.endTurns + 1
		F.turn = F.turn + 1
	end
end
function Game.SelectionListGameNetMessage(message, mission, a, b_, c, d, e)
	if message == GameMessageTypes.GAMEMESSAGE_PUSH_MISSION and mission == MissionTypes.MISSION_SKIP and F.selected then
		F.selected.idle = false
		F.skipped = (F.skipped or 0) + 1
	end
end
function Game.GetGameTurnYear() return -3800 end
function Game.CityPushOrder(city, order, id, alt, replace, ctrl)
	table.insert(F.orders, { city = city.id, order = order, id = id, alt = alt, replace = replace, ctrl = ctrl })
	if order == OrderTypes.ORDER_CONSTRUCT then
		city.building = id
	else
		city.building = nil
		city.unitType = id
	end
end
function Game.DoMinorGoldGift(minor, amount)
	table.insert(F.gifts, { minor = minor, amount = amount })
	F.gold = F.gold - amount
	F.influence = F.influence + amount / 10
end
Network = {
	SendUpdatePolicies = function(id, isPolicy, adopt)
		if isPolicy then F.adopted[id] = true; F.policies = F.policies + 1 else F.openBranches[id] = true; F.branches = F.branches + 1 end
		F.policyDue = false
	end,
	SendResearch = function(tech, free, steal, shift)
		table.insert(F.researchSent, { tech = tech, free = free, steal = steal, shift = shift })
		F.research = tech
	end,
	SendChangeWar = function(team, war) F.war[team] = war end,
}
UI = {
	CanEndTurn = function()
		if F.settling then
			F.settling = false
			return false
		end
		return F.canEndTurn and not F.policyDue and not F.news
	end,
	SelectUnit = function(unit) F.selected = unit end,
	LookAt = function(plot, mode) table.insert(F.lookAt, plot:GetPlotIndex()) end,
	SetDirty = function(bit, value) F.dirty = F.dirty + 1 end,
}

-- Events ------------------------------------------------------------------------------------
local function eventSource()
	return setmetatable({}, { __index = function(t, name)
		local handlers = {}
		local event = { handlers = handlers }
		event.Add = function(fn) table.insert(handlers, fn) end
		event.Remove = function(fn)
			for i = #handlers, 1, -1 do
				if handlers[i] == fn then table.remove(handlers, i) end
			end
		end
		rawset(t, name, event)
		return event
	end })
end
Events = eventSource()
GameEvents = eventSource()
function F.fire(source, name, ...)
	local event = rawget(source, name)
	if not event then return 0 end
	for _, fn in ipairs(event.handlers) do
		fn(...)
	end
	return #event.handlers
end
function F.handlerCount(source, name)
	local event = rawget(source, name)
	return event and #event.handlers or 0
end

-- UI controls -------------------------------------------------------------------------------
local function control()
	return {
		calls = {},
		SetHide = function(self, hidden) self.hidden = hidden end,
		SetWorldPositionVal = function(self, x, y, z) self.x, self.y, self.z = x, y, z end,
		SetToolTipString = function(self, text) self.tooltip = text end,
	}
end
-- As in the game: once its screen is cleared, it takes a moment before the turn may end.
UIManager = { DequeuePopup = function(self, context) if context.news then F.news = false; F.settling = true; F.dequeued = F.dequeued + 1 end end }
ContextPtr = {
	-- Only the technology popup exists here, and it is up while F.news is set.
	LookUpControl = function(self, path)
		if path == "/InGame/TechAwardPopup" then
			return { news = true, IsHidden = function() return not F.news end }
		end
		return nil
	end,
	BuildInstanceForControl = function(self, name, into, parent)
		F.built = F.built + 1
		F.instanceName = name
		into.Anchor = control()
		into.Icon = control()
		F.marker = into
	end,
}
Controls = { SettlerReccomendationStore = { ReleaseChild = function(self, child) F.released = F.released + 1 end } }
function IconHookup(index, size, atlas, icon)
	table.insert(F.icons, { index = index, size = size, atlas = atlas })
end
