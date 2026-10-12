-- Living World: the Civilization V half of the Civ V x Minecraft bridge.
--
-- This file is never installed. The bridge sends it through the game's debug socket into the
-- "GenericWorldAnchor" UI context of a running game and runs it there; it is gone again when
-- the game is closed or another game is loaded. It answers three calls (tick, snap, cmd) by
-- printing tab-separated records, described in docs/PROTOCOL.md.

if not (Game and Map and Players and Teams and ContextPtr) then
	error("NOGAME")
end

-- A newer copy replaces an older one in the same context: let the old one unhook itself first.
if LivingWorld and LivingWorld.shutdown then
	pcall(LivingWorld.shutdown)
end

local LW = { VERSION = 2 }
local P = "LW\t"
local floor = math.floor

local function esc(s)
	s = tostring(s)
	s = s:gsub("\\", "\\\\")
	s = s:gsub("\t", "\\t")
	s = s:gsub("\n", "\\n")
	s = s:gsub("\r", "\\r")
	return s
end

-- One record as a line of text. Arguments must not be nil.
local function line(...)
	local t = { ... }
	for i = 1, #t do
		t[i] = esc(t[i])
	end
	return P .. table.concat(t, "\t")
end

local function b(v)
	return v and 1 or 0
end

-- Calls a method that may not exist in every version or mod of the game.
local function try(obj, name, default, ...)
	local f = obj[name]
	if not f then
		return default
	end
	local ok, r = pcall(f, obj, ...)
	if ok and r ~= nil then
		return r
	end
	return default
end

-- The game's names carry markup meant for its own screens ("Construct a [LINK=IMPROVEMENT_MINE]Mine[\\LINK]",
-- "[ICON_GOLD] Gold"): plain words for ours.
local function plain(text)
	local s = (text or ""):gsub("%[[^%]]*%]", "")
	s = s:gsub("%s+", " ")
	s = s:gsub("^ ", "")
	s = s:gsub(" $", "")
	return s
end

-- The same for a plain function (not a method).
local function tryf(fn, default, ...)
	if not fn then
		return default
	end
	local ok, r = pcall(fn, ...)
	if ok and r ~= nil then
		return r
	end
	return default
end

local function strip(text, prefix)
	text = text or ""
	if text:sub(1, #prefix) == prefix then
		return text:sub(#prefix + 1)
	end
	return text
end

----------------------------------------------------------------------------------------------
-- Identity

local bootCount = (LivingWorldBoots or 0) + 1
LivingWorldBoots = bootCount
-- The address of a fresh table is different in every Lua state the game creates.
LW.boot = tostring({}):gsub("table: ", "") .. "-" .. bootCount

local function gameId()
	local w, h = Map.GetGridSize()
	local hash = 5381
	local n = Map.GetNumPlots()
	for i = 0, n - 1 do
		local plot = Map.GetPlotByIndex(i)
		hash = (hash * 33 + plot:GetTerrainType() * 7 + plot:GetPlotType() * 3 + 11) % 2147483647
	end
	local civ = Players[Game.GetActivePlayer()]:GetCivilizationType()
	return w .. "x" .. h .. "-" .. hash .. "-" .. civ .. "-" .. try(Game, "GetStartYear", 0)
end
LW.game = gameId()

LW.rev = { u = 1, c = 1, p = 1, v = 1 }
LW.hints = {}
LW.hintNumber = 0
LW.done = {}
LW.doneCount = 0

local function heartbeat()
	local ap = Game.GetActivePlayer()
	local w, h = Map.GetGridSize()
	return line("S", LW.VERSION, LW.boot, LW.game, Game.GetGameTurn(), ap, Game.GetActiveTeam(), w, h, b(Map.IsWrapX()),
		LW.rev.u, LW.rev.c, LW.rev.p, LW.rev.v, b(Players[ap]:IsTurnActive()))
end

----------------------------------------------------------------------------------------------
-- Hints: the game's own hooks. They only say "look again"; the data is always re-read.

local hooks = {}

local function hint(kind, ...)
	LW.hintNumber = LW.hintNumber + 1
	if #LW.hints < 48 then
		LW.hints[#LW.hints + 1] = line("E", LW.hintNumber, kind, ...)
	end
end

local function on(source, name, fn)
	local event = source and source[name]
	if event and event.Add then
		local ok = pcall(event.Add, fn)
		if ok then
			hooks[#hooks + 1] = { event, fn }
		end
	end
end

local rev = LW.rev
on(Events, "SerialEventUnitCreated", function(player, unit)
	rev.u = rev.u + 1
	hint("UnitCreated", player or -1, unit or -1)
end)
on(Events, "SerialEventUnitDestroyed", function(player, unit)
	rev.u = rev.u + 1
	hint("UnitDestroyed", player or -1, unit or -1)
end)
on(Events, "SerialEventUnitSetDamage", function(player, unit)
	rev.u = rev.u + 1
	hint("UnitDamaged", player or -1, unit or -1)
end)
on(Events, "SerialEventUnitTeleportedToHex", function()
	rev.u = rev.u + 1
end)
on(Events, "SerialEventUnitMove", function()
	rev.u = rev.u + 1
end)
on(Events, "SerialEventUnitMoveToHexes", function()
	rev.u = rev.u + 1
end)
on(GameEvents, "UnitSetXY", function()
	rev.u = rev.u + 1
end)
on(Events, "SerialEventCityCreated", function(_, player, city)
	rev.c = rev.c + 1
	rev.p = rev.p + 1
	hint("CityCreated", player or -1, city or -1)
end)
on(Events, "SerialEventCityCaptured", function(_, player, city, newPlayer)
	rev.c = rev.c + 1
	rev.p = rev.p + 1
	hint("CityCaptured", player or -1, city or -1, newPlayer or -1)
end)
on(Events, "SerialEventCityDestroyed", function(_, player, city)
	rev.c = rev.c + 1
	rev.p = rev.p + 1
	hint("CityDestroyed", player or -1, city or -1)
end)
on(Events, "SerialEventCityPopulationChanged", function()
	rev.c = rev.c + 1
end)
on(Events, "SerialEventCityInfoDirty", function()
	rev.c = rev.c + 1
end)
on(Events, "SpecificCityInfoDirty", function()
	rev.c = rev.c + 1
end)
on(Events, "ActivePlayerTurnStart", function()
	rev.u = rev.u + 1
	rev.c = rev.c + 1
	rev.p = rev.p + 1
	hint("TurnStart", Game.GetGameTurn())
end)
on(Events, "ActivePlayerTurnEnd", function()
	hint("TurnEnd", Game.GetGameTurn())
end)
on(Events, "WarStateChanged", function(team1, team2, war)
	rev.v = rev.v + 1
	hint("WarState", team1 or -1, team2 or -1, b(war))
end)
on(Events, "HexFOWStateChanged", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventHexCultureChanged", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventImprovementCreated", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventImprovementDestroyed", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventRoadCreated", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventRoadDestroyed", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventForestCreated", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventForestRemoved", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventJungleCreated", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventJungleRemoved", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventFeatureCreated", function()
	rev.p = rev.p + 1
end)
on(Events, "SerialEventFeatureRemoved", function()
	rev.p = rev.p + 1
end)

----------------------------------------------------------------------------------------------
-- The player's marker on the map: a stock world-anchored icon of this context, reused.

local geo = {}
do
	local x00, y00 = GridToWorld(0, 0)
	local x10, y10 = GridToWorld(1, 0)
	local x01, y01 = GridToWorld(0, 1)
	local x02, y02 = GridToWorld(0, 2)
	geo = { x00 = x00, y00 = y00, x10 = x10, y10 = y10, x01 = x01, y01 = y01, x02 = x02, y02 = y02,
		dx = x10 - x00, dy = (y02 - y00) / 2 }
end

local marker = nil
local lookedAt = -1
local markerPlot = -1

local function hideMarker()
	if marker then
		marker.Anchor:SetHide(true)
	end
	markerPlot = -1
end

local function showMarker(u, v, px, py, follow, label)
	if not marker then
		marker = {}
		ContextPtr:BuildInstanceForControl("SettlerReccomendation", marker, Controls.SettlerReccomendationStore)
		-- The face of the player's own leader: this marker is "you".
		local leader = GameInfo.Leaders[Players[Game.GetActivePlayer()]:GetLeaderType()]
		if leader and IconHookup then
			pcall(IconHookup, leader.PortraitIndex, 64, leader.IconAtlas, marker.Icon)
		end
	end
	local z = 0
	local plot = px >= 0 and Map.GetPlot(px, py) or nil
	if plot then
		local _, _, pz = GridToWorld(px, py)
		z = pz or 0
	end
	local wx, wy = geo.x00 + u * geo.dx, geo.y00 + v * geo.dy
	marker.Anchor:SetHide(false)
	marker.Anchor:SetWorldPositionVal(wx, wy, z)
	if label ~= LW.label then
		LW.label = label
		marker.Icon:SetToolTipString(label)
	end
	-- Stepping into another hex: the name floats up from the marker, the way the game shows
	-- its own "+2 gold" texts.
	local here = plot and plot:GetPlotIndex() or -1
	if here ~= markerPlot then
		markerPlot = here
		if here >= 0 and Events.AddPopupTextEvent then
			pcall(Events.AddPopupTextEvent, { x = wx, y = wy, z = z }, label, 0)
		end
	end
	if follow and plot then
		local index = plot:GetPlotIndex()
		if index ~= lookedAt then
			lookedAt = index
			UI.LookAt(plot, 0)
		end
	else
		lookedAt = -1
	end
end

function LW.tick(seq, u, v, px, py, show, follow, label)
	print(P .. ">\t" .. seq)
	local n = 0
	local ok, err = pcall(function()
		print(heartbeat())
		n = n + 1
		if show then
			showMarker(u, v, px, py, follow, label)
		else
			hideMarker()
		end
	end)
	if not ok then
		print(line("X", "tick", err))
		n = n + 1
	end
	local hints = LW.hints
	LW.hints = {}
	for i = 1, #hints do
		print(hints[i])
	end
	print(P .. "<\t" .. seq .. "\t" .. (n + #hints))
end

----------------------------------------------------------------------------------------------
-- Snapshots. Only what the active team knows: plots it has revealed, units it can see.

local function statics(out)
	out[#out + 1] = line("G", geo.x00, geo.y00, geo.x10, geo.y10, geo.x01, geo.y01, geo.x02, geo.y02)
	local rows = {}
	local function table_(kind, source)
		if not source then
			return
		end
		for row in source() do
			local name = row.Description and Locale.ConvertTextKey(row.Description) or row.Type
			rows[#rows + 1] = line("L", kind, row.ID, row.Type or "", name or "")
		end
	end
	table_("T", GameInfo.Terrains)
	table_("F", GameInfo.Features)
	table_("I", GameInfo.Improvements)
	table_("R", GameInfo.Resources)
	table_("O", GameInfo.Routes)
	out[#out + 1] = line("N", "L", #rows)
	for i = 1, #rows do
		out[#out + 1] = rows[i]
	end
end

local function rgb(color)
	if not color then
		return 0x808080
	end
	local r = floor((color.x or 0.5) * 255 + 0.5)
	local g = floor((color.y or 0.5) * 255 + 0.5)
	local bl = floor((color.z or 0.5) * 255 + 0.5)
	return r * 65536 + g * 256 + bl
end

local function civs(out)
	local ap = Game.GetActivePlayer()
	local team = Teams[Game.GetActiveTeam()]
	local rows = {}
	for id = 0, GameDefines.MAX_PLAYERS - 1 do
		local player = Players[id]
		-- (IsEverAlive is true for every city-state slot of the game, used or not.)
		if player and player:IsAlive() then
			local t = player:GetTeam()
			local met = id == ap or t == Game.GetActiveTeam() or team:IsHasMet(t)
			local flags = b(player:IsAlive()) + 2 * b(player:IsMinorCiv()) + 4 * b(player:IsBarbarian()) + 8 * b(player:IsHuman())
				+ 16 * b(t ~= Game.GetActiveTeam() and team:IsAtWar(t)) + 32 * b(met)
			-- The game returns (icon colour, background colour) for a civilization and the reverse
			-- for a city-state. The background is the one its borders and flags are drawn in.
			local accent, main = nil, nil
			pcall(function()
				accent, main = player:GetPlayerColors()
			end)
			if player:IsMinorCiv() then
				accent, main = main, accent
			end
			local info = GameInfo.Civilizations[player:GetCivilizationType()]
			if met or player:IsBarbarian() then
				local era = GameInfo.Eras and GameInfo.Eras[try(player, "GetCurrentEra", 0)]
				rows[#rows + 1] = line("V", id, t, info and info.Type or "", player:GetCivilizationShortDescription(),
					player:GetCivilizationAdjective(), player:GetName(), flags, rgb(main), rgb(accent),
					era and era.Type or "", info and info.ArtStyleType or "")
			else
				-- Not met yet: the slot exists, who it is stays unknown.
				rows[#rows + 1] = line("V", id, t, "", "Unmet civilization", "Unknown", "", flags, 0x808080, 0x404040, "", "")
			end
		end
	end
	out[#out + 1] = line("N", "V", #rows)
	for i = 1, #rows do
		out[#out + 1] = rows[i]
	end
end

local function plots(out)
	local team = Game.GetActiveTeam()
	local w, h = Map.GetGridSize()
	local rows = {}
	for y = 0, h - 1 do
		local cells = {}
		local size = 0
		local x0 = 0
		for x = 0, w - 1 do
			local plot = Map.GetPlot(x, y)
			local cell
			if not plot:IsRevealed(team, false) then
				cell = "?"
			else
				local elevation = plot:IsMountain() and 2 or (plot:IsHills() and 1 or 0)
				local visible = plot:IsVisible(team, false)
				local flags = 2 * b(visible) + 4 * b(plot:IsCity()) + 8 * b(plot:IsWater()) + 16 * b(plot:IsLake())
					+ 32 * b(plot:IsImprovementPillaged())
				local rivers = b(plot:IsWOfRiver()) + 2 * b(plot:IsNWOfRiver()) + 4 * b(plot:IsNEOfRiver())
				cell = plot:GetTerrainType() .. "," .. plot:GetFeatureType() .. "," .. elevation .. ","
					.. plot:GetRevealedOwner(team, false) .. "," .. plot:GetRevealedImprovementType(team, false) .. ","
					.. plot:GetResourceType(team) .. "," .. plot:GetRevealedRouteType(team, false) .. "," .. rivers .. "," .. flags
			end
			-- One print() carries about 4 KB at most: start a new record well before that.
			if size + #cell + 1 > 3000 then
				rows[#rows + 1] = line("P", y, x0, table.concat(cells, ";"))
				cells = {}
				size = 0
				x0 = x
			end
			cells[#cells + 1] = cell
			size = size + #cell + 1
		end
		rows[#rows + 1] = line("P", y, x0, table.concat(cells, ";"))
	end
	out[#out + 1] = line("N", "P", #rows)
	for i = 1, #rows do
		out[#out + 1] = rows[i]
	end
end

local function unitRow(unit, team)
	local info = GameInfo.Units[unit:GetUnitType()]
	local domain = GameInfo.Domains and GameInfo.Domains[unit:GetDomainType()]
	local flags = b(unit:IsEmbarked()) + 2 * b(try(unit, "GetFortifyTurns", 0) > 0) + 4 * b(unit:IsCombatUnit())
		+ 8 * b(try(unit, "IsGarrisoned", false)) + 16 * b(try(unit, "IsGreatPerson", false))
	return line("U", unit:GetOwner(), unit:GetID(), info and info.Type or "", unit:GetName(), unit:GetX(), unit:GetY(),
		unit:GetCurrHitPoints(), unit:GetMaxHitPoints(), unit:MovesLeft(), unit:MaxMoves(),
		try(unit, "GetBaseCombatStrength", 0), try(unit, "GetBaseRangedCombatStrength", 0), domain and domain.Type or "",
		try(unit, "GetLevel", 1), try(unit, "GetExperience", 0), flags)
end

local function units(out)
	local team = Game.GetActiveTeam()
	local rows = {}
	for id = 0, GameDefines.MAX_PLAYERS - 1 do
		local player = Players[id]
		if player and player:IsAlive() then
			for unit in player:Units() do
				local plot = unit:GetPlot()
				if plot and plot:IsVisible(team, false) and not unit:IsInvisible(team, false) and not unit:IsDead() then
					local ok, row = pcall(unitRow, unit, team)
					if ok then
						rows[#rows + 1] = row
					end
				end
			end
		end
	end
	out[#out + 1] = line("N", "U", #rows)
	for i = 1, #rows do
		out[#out + 1] = rows[i]
	end
end

local function production(city)
	local unit, building = city:GetProductionUnit(), city:GetProductionBuilding()
	local project, process = city:GetProductionProject(), city:GetProductionProcess()
	if unit ~= -1 and GameInfo.Units[unit] then
		return GameInfo.Units[unit].Type
	elseif building ~= -1 and GameInfo.Buildings[building] then
		return GameInfo.Buildings[building].Type
	elseif project ~= -1 and GameInfo.Projects[project] then
		return GameInfo.Projects[project].Type
	elseif process ~= -1 and GameInfo.Processes[process] then
		return GameInfo.Processes[process].Type
	end
	return ""
end

-- Every building type once, with whether it is a world wonder (one per world).
local buildingRows = {}
-- Guarded: this runs while the script loads, and a surprise in a modded building table must
-- cost the building list, not the whole script.
pcall(function()
	for row in GameInfo.Buildings() do
		local class = row.BuildingClass and GameInfo.BuildingClasses and GameInfo.BuildingClasses[row.BuildingClass] or nil
		buildingRows[#buildingRows + 1] = { id = row.ID, key = strip(row.Type, "BUILDING_"),
			wonder = (class and (class.MaxGlobalInstances or 0) > 0) or false }
	end
end)

-- The buildings and wonders standing in one of the viewer's own cities, as two comma lists.
local function cityBuildings(city)
	local buildings, wonders = {}, {}
	for i = 1, #buildingRows do
		local row = buildingRows[i]
		if city:IsHasBuilding(row.id) then
			if row.wonder then
				wonders[#wonders + 1] = row.key
			else
				buildings[#buildings + 1] = row.key
			end
		end
	end
	return table.concat(buildings, ","):sub(1, 900), table.concat(wonders, ","):sub(1, 500)
end

local function cityRow(city, mine)
	local plot = city:Plot()
	local flags = b(city:IsCapital()) + 2 * b(city:IsPuppet()) + 4 * b(city:IsOccupied()) + 8 * b(city:IsRazing())
		+ 16 * b(city:IsResistance()) + 32 * b(plot:IsCoastalLand()) + 64 * b(mine)
	local max = try(city, "GetMaxHitPoints", 200)
	local kind, name, done, needed, turns, food, growth = "", "", 0, 0, 0, 0, 0
	if mine then
		kind = production(city)
		if kind ~= "" then
			name = Locale.ConvertTextKey(city:GetProductionNameKey())
			done = city:GetProduction()
			needed = city:GetProductionNeeded()
			turns = city:GetProductionTurnsLeft()
		end
		food = floor(city:GetFood())
		growth = city:GrowthThreshold()
	end
	local buildings, wonders = "", ""
	if mine then
		local ok, b, w = pcall(cityBuildings, city)
		if ok then
			buildings, wonders = b, w
		end
	end
	return line("T", city:GetOwner(), city:GetID(), city:GetName(), city:GetX(), city:GetY(), city:GetPopulation(), flags,
		max - city:GetDamage(), max, kind, name, done, needed, turns, food, growth, floor(try(city, "GetStrengthValue", 0) / 100),
		buildings, wonders)
end

local function cities(out)
	local ap = Game.GetActivePlayer()
	local team = Game.GetActiveTeam()
	local rows = {}
	for id = 0, GameDefines.MAX_PLAYERS - 1 do
		local player = Players[id]
		if player and player:IsAlive() then
			for city in player:Cities() do
				if city:Plot():IsRevealed(team, false) and try(city, "IsRevealed", true, team, false) then
					local ok, row = pcall(cityRow, city, id == ap)
					if ok then
						rows[#rows + 1] = row
					end
				end
			end
		end
	end
	out[#out + 1] = line("N", "T", #rows)
	for i = 1, #rows do
		out[#out + 1] = rows[i]
	end
end

-- The viewer's own empire at a glance, the technologies it knows, and how the others stand to it.
-- Screens of the game that only tell something and wait for "Continue": a technology learned, a
-- wonder built, a new era. While one is up the turn cannot end, and a player who is in Minecraft
-- is not there to press the button. The same news is told in Minecraft, so asking for the end of
-- the turn puts them away. Screens that ask for a decision are never touched.
local NEWS = { "TechAwardPopup", "WonderPopup", "NewEraPopup", "NaturalWonderPopup", "GoodyHutPopup", "BarbarianCampPopup", "GoldenAgePopup",
	"GreatPersonRewardPopup", "CityStateGreetingPopup", "VoteResultsPopup", "WhosWinningPopup", "GreatWorkSplash", "LeagueSplash" }

-- How many of them are on screen; with close, they are put away the way their own button does it.
local function news(close)
	local count = 0
	for i = 1, #NEWS do
		local ok, context = pcall(function()
			return ContextPtr:LookUpControl("/InGame/" .. NEWS[i])
		end)
		if ok and context then
			local shown = false
			pcall(function()
				shown = context:IsHidden() == false
			end)
			if shown then
				count = count + 1
				if close then
					pcall(function()
						UIManager:DequeuePopup(context)
					end)
				end
			end
		end
	end
	return count
end

-- What stops the Next Turn button right now, as a word ("POLICY", "PRODUCTION", "NEWS"), or "" when nothing does.
local function blocking(player)
	local kind = try(player, "GetEndTurnBlockingType", -1)
	if EndTurnBlockingTypes and type(kind) == "number" and kind >= 0 then
		for name, value in pairs(EndTurnBlockingTypes) do
			if value == kind and name:sub(1, 17) == "ENDTURN_BLOCKING_" then
				return name:sub(18)
			end
		end
	end
	if news(false) > 0 then
		return "NEWS"
	end
	return ""
end

-- The same in words, for the player.
local function waitingFor(player)
	local word = blocking(player)
	local words = {
		POLICY = "adopt a social policy", FREE_POLICY = "adopt a social policy", RESEARCH = "choose what to research",
		FREE_TECH = "choose a free technology", PRODUCTION = "choose what a city builds", UNITS = "a unit is waiting for orders",
		UNIT_NEEDS_ORDERS = "a unit is waiting for orders", STACKED_UNITS = "two units share a hex and one must move",
		UNIT_PROMOTION = "a unit can be promoted", CITY_RANGE_ATTACK = "a city can fire at an enemy",
		FOUND_PANTHEON = "choose a pantheon", FOUND_RELIGION = "found your religion", ENHANCE_RELIGION = "enhance your religion",
	}
	if words[word] then
		return words[word]
	end
	if word == "" then
		return "something in its own window"
	end
	return word:lower():gsub("_", " ") .. ", in its own window"
end

local function empire(out)
	local ap = Game.GetActivePlayer()
	local team = Game.GetActiveTeam()
	local player = Players[ap]
	local era = GameInfo.Eras and GameInfo.Eras[try(player, "GetCurrentEra", 0)]
	local tech = try(player, "GetCurrentResearch", -1)
	local techRow = tech >= 0 and GameInfo.Technologies and GameInfo.Technologies[tech] or nil
	out[#out + 1] = line("Y", floor(try(player, "GetGold", 0)), floor(try(player, "CalculateGoldRate", 0)), floor(try(player, "GetScience", 0)),
		floor(try(player, "GetExcessHappiness", 0)), floor(try(player, "GetJONSCulture", 0)), floor(try(player, "GetTotalJONSCulturePerTurn", 0)),
		floor(try(player, "GetNextPolicyCost", 0)), floor(try(player, "GetGoldenAgeTurns", 0)), era and era.Type or "",
		tryf(Game.GetGameTurnYear, 0), techRow and techRow.Type or "", techRow and Locale.ConvertTextKey(techRow.Description) or "",
		techRow and try(player, "GetResearchTurnsLeft", 0, tech, true) or 0,
		try(player, "GetNumPolicies", 0) + try(player, "GetNumPolicyBranchesUnlocked", 0), blocking(player))

	local rows, current, size = {}, {}, 0
	local techs = Teams[team].GetTeamTechs and Teams[team]:GetTeamTechs() or nil
	if techs and GameInfo.Technologies then
		for row in GameInfo.Technologies() do
			if techs:HasTech(row.ID) then
				local key = strip(row.Type, "TECH_")
				if size + #key + 1 > 3000 then
					rows[#rows + 1] = line("K", table.concat(current, ","))
					current, size = {}, 0
				end
				current[#current + 1] = key
				size = size + #key + 1
			end
		end
	end
	rows[#rows + 1] = line("K", table.concat(current, ","))
	out[#out + 1] = line("N", "K", #rows)
	for i = 1, #rows do
		out[#out + 1] = rows[i]
	end

	rows = {}
	for id = 0, GameDefines.MAX_PLAYERS - 1 do
		local other = Players[id]
		if other and id ~= ap and other:IsAlive() and not other:IsBarbarian() and Teams[team]:IsHasMet(other:GetTeam()) then
			local ok, row = pcall(function()
				if other:IsMinorCiv() then
					-- A city-state: influence, standing, and what it is asking for.
					local quests = {}
					if MinorCivQuestTypes and other.IsMinorCivDisplayedQuestForPlayer then
						for name, value in pairs(MinorCivQuestTypes) do
							if type(value) == "number" and value >= 0 and other:IsMinorCivDisplayedQuestForPlayer(ap, value) then
								quests[#quests + 1] = strip(name, "MINOR_CIV_QUEST_")
							end
						end
					end
					table.sort(quests)
					local trait = GameInfo.MinorCivTraits and GameInfo.MinorCivTraits[try(other, "GetMinorCivTrait", -1)]
					return line("M", id, 1, try(other, "GetMinorCivFriendshipWithMajor", 0, ap),
						(try(other, "IsAllies", false, ap) and 2) or (try(other, "IsFriends", false, ap) and 1) or 0,
						trait and strip(trait.Type, "MINOR_TRAIT_") or "", try(other, "GetAlly", -1), table.concat(quests, ","),
						GameDefines.MINOR_GOLD_GIFT_SMALL or 250, GameDefines.MINOR_GOLD_GIFT_MEDIUM or 500, GameDefines.MINOR_GOLD_GIFT_LARGE or 1000)
				end
				-- Another civilization: how it seems to feel about the viewer, as the game's own screens show it.
				local approach = try(other, "GetApproachTowardsUsGuess", -1, ap)
				local word = ""
				if MajorCivApproachTypes then
					for name, value in pairs(MajorCivApproachTypes) do
						if value == approach then
							word = strip(name, "MAJOR_CIV_APPROACH_")
						end
					end
				end
				return line("M", id, 0, 0, b(try(other, "IsDoF", false, ap)) + 2 * b(try(player, "IsDenouncedPlayer", false, id))
					+ 4 * b(try(Teams[team], "CanDeclareWar", false, other:GetTeam())), word, -1, "", 0, 0, 0)
			end)
			if ok then
				rows[#rows + 1] = row
			end
		end
	end
	out[#out + 1] = line("N", "M", #rows)
	for i = 1, #rows do
		out[#out + 1] = rows[i]
	end
end

-- mask: 1 geometry + type tables + civs, 2 plots, 4 units, 8 cities, 16 civs only, 32 empire
function LW.snap(seq, mask)
	print(P .. ">\t" .. seq)
	local out = { heartbeat() }
	local ok, err = pcall(function()
		if mask % 2 >= 1 then
			statics(out)
		end
		if mask % 2 >= 1 or mask % 32 >= 16 then
			civs(out)
		end
		if mask % 4 >= 2 then
			plots(out)
		end
		if mask % 8 >= 4 then
			units(out)
		end
		if mask % 16 >= 8 then
			cities(out)
		end
		if mask % 64 >= 32 then
			empire(out)
		end
	end)
	if not ok then
		out[#out + 1] = line("X", "snap", err)
	end
	for i = 1, #out do
		print(out[i])
	end
	print(P .. "<\t" .. seq .. "\t" .. #out)
end

----------------------------------------------------------------------------------------------
-- Choices: what the game would offer the player right now, asked for when a menu is opened.

local function hasTech(key)
	if not key then
		return true
	end
	local row = GameInfo.Technologies and GameInfo.Technologies[key]
	if not row then
		return true
	end
	local techs = Teams[Game.GetActiveTeam()]:GetTeamTechs()
	return techs:HasTech(row.ID)
end

-- Yes/no columns of the game's tables arrive as booleans; a number is accepted too.
local function yes(value)
	return value == true or value == 1
end

-- The builds a Worker may do, from the game's own table. The table of all builds also holds
-- what only a Great Person does (an Academy, a Citadel), and those are not for sale.
local workerBuilds = nil
pcall(function()
	local set, count = {}, 0
	for row in GameInfo.Unit_Builds() do
		if row.UnitType == "UNIT_WORKER" then
			set[row.BuildType] = true
			count = count + 1
		end
	end
	if count > 0 then
		workerBuilds = set
	end
end)

-- What one of the game's "builds" (the things a Worker does) would do on a plot, or nil when the
-- viewer may not do it there: "imp" + improvement id, "route" + route id, or "clear" (remove the
-- forest, jungle or marsh). The same test serves the menu and the command.
local function work(plot, row)
	local ap = Game.GetActivePlayer()
	if not plot or plot:GetOwner() ~= ap or plot:IsCity() or plot:IsWater() or plot:IsMountain() then
		return nil
	end
	if yes(row.Repair) or yes(row.RemoveRoute) or not hasTech(row.PrereqTech) then
		return nil
	end
	if workerBuilds and not workerBuilds[row.Type] then
		return nil
	end
	if plot.CanBuild then
		local ok, can = pcall(plot.CanBuild, plot, row.ID, ap)
		if ok and can == false then
			return nil
		end
	end
	local feature = plot:GetFeatureType()
	local featureRow = feature >= 0 and GameInfo.Features[feature] or nil
	-- What this build does to the forest, jungle or marsh that is here, if the game has a rule for it.
	local clearing = nil
	if featureRow and GameInfo.BuildFeatures then
		for candidate in GameInfo.BuildFeatures() do
			if candidate.BuildType == row.Type and candidate.FeatureType == featureRow.Type then
				clearing = candidate
			end
		end
	end
	if clearing and not hasTech(clearing.PrereqTech) then
		return nil
	end
	local remove = (clearing and yes(clearing.Remove)) and true or false
	if row.ImprovementType then
		local improvement = GameInfo.Improvements[row.ImprovementType]
		if not improvement or plot:GetImprovementType() == improvement.ID then
			return nil
		end
		-- Never a Great Person's improvement, and another civilization's own one only for that civilization.
		if yes(improvement.CreatedByGreatPerson) then
			return nil
		end
		if yes(improvement.SpecificCivRequired) then
			local civ = GameInfo.Civilizations and GameInfo.Civilizations[Players[ap]:GetCivilizationType()]
			if not civ or civ.Type ~= improvement.CivilizationType then
				return nil
			end
		end
		if plot.CanHaveImprovement then
			local ok, can = pcall(plot.CanHaveImprovement, plot, improvement.ID, Game.GetActiveTeam())
			if ok and can == false then
				return nil
			end
		end
		return "imp", improvement.ID, remove
	elseif row.RouteType then
		local route = GameInfo.Routes[row.RouteType]
		-- GetRevealedRouteType is the call the map picture already uses; it stands in when the other is missing.
		local have = plot.GetRouteType and plot:GetRouteType() or plot:GetRevealedRouteType(Game.GetActiveTeam(), false)
		if not route or have >= route.ID then
			return nil
		end
		return "route", route.ID
	elseif remove then
		return "clear", clearing.Production or 0, true
	end
	return nil
end

-- kind "build": what city a can start on. "research": what can be researched. "works": what can
-- be built on plot (a, c).
function LW.opts(seq, kind, a, c)
	print(P .. ">\t" .. seq)
	local out = {}
	local ok, err = pcall(function()
		local player = Players[Game.GetActivePlayer()]
		if kind == "build" then
			local city = player:GetCityByID(a)
			if not city then
				return
			end
			local function add(order, source, can, turns)
				if not source then
					return
				end
				for row in source() do
					local allowed = false
					pcall(function()
						allowed = can(row.ID)
					end)
					if allowed and #out < 80 then
						out[#out + 1] = line("O", order, row.ID, row.Type or "", plain(Locale.ConvertTextKey(row.Description or row.Type or "")),
							turns and tryf(turns, 0, row.ID) or 0)
					end
				end
			end
			add(1, GameInfo.Buildings, function(id) return city:CanConstruct(id) end, function(id) return city:GetBuildingProductionTurnsLeft(id) end)
			add(0, GameInfo.Units, function(id) return city:CanTrain(id) end, function(id) return city:GetUnitProductionTurnsLeft(id) end)
			add(2, GameInfo.Projects, function(id) return city:CanCreate(id) end, function(id) return city:GetProjectProductionTurnsLeft(id) end)
			add(3, GameInfo.Processes, function(id) return city:CanMaintain(id) end, nil)
		elseif kind == "research" then
			for row in GameInfo.Technologies() do
				if player:CanResearch(row.ID) and #out < 60 then
					out[#out + 1] = line("O", 0, row.ID, row.Type, plain(Locale.ConvertTextKey(row.Description)), try(player, "GetResearchTurnsLeft", 0, row.ID, true))
				end
			end
		elseif kind == "policy" then
			-- Group 5: a branch of the policy tree that can be opened. Group 6: a policy that can be adopted.
			if GameInfo.PolicyBranchTypes then
				for row in GameInfo.PolicyBranchTypes() do
					-- The game's own check says yes for a branch that is open already (seen on the real game);
					-- its policy screen asks "open already?" first, and so does this.
					if not try(player, "IsPolicyBranchUnlocked", false, row.ID) and try(player, "CanUnlockPolicyBranch", false, row.ID) and #out < 40 then
						out[#out + 1] = line("O", 5, row.ID, row.Type or "", plain(Locale.ConvertTextKey(row.Description or row.Type or "")), 0)
					end
				end
			end
			if GameInfo.Policies then
				for row in GameInfo.Policies() do
					if not try(player, "HasPolicy", false, row.ID) and try(player, "CanAdoptPolicy", false, row.ID) and #out < 40 then
						out[#out + 1] = line("O", 6, row.ID, row.Type or "", plain(Locale.ConvertTextKey(row.Description or row.Type or "")), 0)
					end
				end
			end
		elseif kind == "works" then
			local plot = Map.GetPlot(a, c)
			if plot and GameInfo.Builds then
				for row in GameInfo.Builds() do
					-- Each build on its own: one the game cannot answer for must not hide the others.
					local fine, what = pcall(work, plot, row)
					if fine and what and #out < 30 then
						out[#out + 1] = line("O", 4, row.ID, row.Type or "", plain(Locale.ConvertTextKey(row.Description or row.Type or "")), 0)
					end
				end
			end
		end
	end)
	if not ok then
		out[#out + 1] = line("X", "opts", err)
	end
	print(line("N", "O", #out))
	for i = 1, #out do
		print(out[i])
	end
	print(P .. "<\t" .. seq .. "\t" .. (#out + 1))
end

----------------------------------------------------------------------------------------------
-- Commands: a fixed list of things the Minecraft side may ask for. Nothing else is reachable.

local function refresh()
	if UI.SetDirty and InterfaceDirtyBits then
		pcall(UI.SetDirty, InterfaceDirtyBits.GameData_DIRTY_BIT, true)
		pcall(UI.SetDirty, InterfaceDirtyBits.CityInfo_DIRTY_BIT, true)
		pcall(UI.SetDirty, InterfaceDirtyBits.UnitInfo_DIRTY_BIT, true)
	end
end

local function apply(id, op, owner, entity, x, y, amount)
	local ap = Game.GetActivePlayer()
	local team = Game.GetActiveTeam()
	if owner ~= ap then
		return line("R", id, 0, "NOT_YOURS", "That does not belong to your civilization.")
	end
	if Game.IsNetworkMultiPlayer() then
		return line("R", id, 0, "MULTIPLAYER", "Living World does not change multiplayer games.")
	end
	local player = Players[ap]
	if not player:IsTurnActive() then
		return line("R", id, 0, "NOT_YOUR_TURN", "It is not your turn in Civilization V.")
	end
	if op == "turn" then
		-- What the Next Turn button does, and only when the game would let that button do it. The
		-- button looks at what the game is waiting for; UI.CanEndTurn alone says yes while a unit
		-- still needs orders, and the end-turn control then does nothing (seen on the real game).
		local closed = news(true)
		local word = blocking(player)
		if word == "UNITS" or word == "UNIT_NEEDS_ORDERS" then
			-- Whoever has no orders waits this turn, as pressing "skip turn" for each of them would.
			local skip = MissionTypes and MissionTypes.MISSION_SKIP or (GameInfoTypes and GameInfoTypes.MISSION_SKIP)
			local waiting = 0
			if skip then
				for unit in player:Units() do
					local ready = false
					pcall(function()
						ready = unit:IsReadyToMove() and not unit:IsAutomated() and not unit:IsDelayedDeath()
					end)
					if ready then
						UI.SelectUnit(unit)
						Game.SelectionListGameNetMessage(GameMessageTypes.GAMEMESSAGE_PUSH_MISSION, skip, 0, 0, 0, false, false)
						waiting = waiting + 1
					end
				end
			end
			if waiting > 0 then
				return line("R", id, 0, "IDLE", "Your units without orders wait this turn: ask again.")
			end
		end
		if (UI.CanEndTurn and not UI.CanEndTurn()) or (word ~= "" and word ~= "NEWS") then
			if closed > 0 and (word == "" or word == "NEWS") then
				-- The game needs a moment to see that its screen is clear; what it wants next shows then.
				return line("R", id, 0, "NEWS", "Civilization V had news on its screen. It has been put away: ask again.")
			end
			return line("R", id, 0, "BLOCKED", "Civilization V still needs a decision from you first: " .. waitingFor(player) .. ".")
		end
		local before = Game.GetGameTurn()
		Game.DoControl(GameInfoTypes.CONTROL_ENDTURN)
		return line("R", id, 1, "OK", "Turn ended.", "before=" .. before, "after=" .. (before + 1))
	end
	if op == "move" then
		local unit = player:GetUnitByID(entity)
		if not unit or unit:IsDead() then
			return line("R", id, 0, "NO_UNIT", "That unit is no longer there.")
		end
		local plot = Map.GetPlot(x, y)
		if not plot or not plot:IsRevealed(team, false) then
			return line("R", id, 0, "BAD_PLOT", "That spot is not on your map.")
		end
		-- The same path the game's own UI takes for a right-click move.
		UI.SelectUnit(unit)
		Game.SelectionListMove(plot, false, false, false)
		rev.u = rev.u + 1
		return line("R", id, 1, "OK", "Order given.", "x=" .. unit:GetX(), "y=" .. unit:GetY())
	elseif op == "prod" then
		local city = player:GetCityByID(entity)
		if not city then
			return line("R", id, 0, "NO_CITY", "That city is no longer yours.")
		end
		if amount < 1 or amount > 60 then
			return line("R", id, 0, "BAD_AMOUNT", "A delivery must be between 1 and 60 hammers.")
		end
		if production(city) == "" then
			return line("R", id, 0, "IDLE", city:GetName() .. " is not building anything right now.")
		end
		local before = city:GetProduction()
		if city.ChangeProduction then
			city:ChangeProduction(amount)
		else
			city:SetProduction(before + amount)
		end
		rev.c = rev.c + 1
		refresh()
		return line("R", id, 1, "OK", "Delivered to " .. city:GetName() .. ".", "before=" .. before, "after=" .. city:GetProduction())
	elseif op == "heal" then
		local unit = player:GetUnitByID(entity)
		if not unit or unit:IsDead() then
			return line("R", id, 0, "NO_UNIT", "That unit is no longer there.")
		end
		if amount < 1 or amount > 50 then
			return line("R", id, 0, "BAD_AMOUNT", "Healing must be between 1 and 50 hit points.")
		end
		local before = unit:GetCurrHitPoints()
		if unit:GetDamage() <= 0 then
			return line("R", id, 0, "HEALTHY", "That unit is not wounded.")
		end
		unit:SetDamage(math.max(0, unit:GetDamage() - amount))
		rev.u = rev.u + 1
		refresh()
		return line("R", id, 1, "OK", "Patched up.", "before=" .. before, "after=" .. unit:GetCurrHitPoints())
	end
	if op == "build" then
		-- x: 0 train a unit, 1 construct a building, 2 create a project, 3 maintain a process. y: which.
		local city = player:GetCityByID(entity)
		if not city then
			return line("R", id, 0, "NO_CITY", "That city is no longer yours.")
		end
		local orders = { [0] = OrderTypes.ORDER_TRAIN, [1] = OrderTypes.ORDER_CONSTRUCT, [2] = OrderTypes.ORDER_CREATE, [3] = OrderTypes.ORDER_MAINTAIN }
		local can = false
		if x == 0 then
			can = city:CanTrain(y)
		elseif x == 1 then
			can = city:CanConstruct(y)
		elseif x == 2 then
			can = city:CanCreate(y)
		elseif x == 3 then
			can = city:CanMaintain(y)
		end
		if not can or not orders[x] then
			return line("R", id, 0, "CANNOT", city:GetName() .. " cannot build that right now.")
		end
		-- As the production screen does: replace what is being built.
		Game.CityPushOrder(city, orders[x], y, false, true, true)
		rev.c = rev.c + 1
		return line("R", id, 1, "OK", city:GetName() .. " has new orders.")
	elseif op == "research" then
		if not player:CanResearch(y) then
			return line("R", id, 0, "CANNOT", "That cannot be researched right now.")
		end
		Network.SendResearch(y, player:GetNumFreeTechs(), -1, false)
		rev.v = rev.v + 1
		return line("R", id, 1, "OK", "Research chosen.")
	elseif op == "reveal" then
		-- The player walked to (x, y): the land around is now explored, as if a scout stood there.
		local plot = Map.GetPlot(x, y)
		if not plot or not plot:IsRevealed(team, false) then
			return line("R", id, 0, "BAD_PLOT", "That spot is not on your map.")
		end
		local radius = math.max(1, math.min(2, amount))
		local w, h = Map.GetGridSize()
		local found = 0
		for dy = -radius, radius do
			for dx = -radius - 1, radius + 1 do
				local px, py = x + dx, y + dy
				if Map.IsWrapX() then
					px = px % w
				end
				local other = px >= 0 and px < w and py >= 0 and py < h and Map.GetPlot(px, py) or nil
				if other and not other:IsRevealed(team, false) and Map.PlotDistance(x, y, px, py) <= radius then
					other:SetRevealed(team, true)
					found = found + 1
				end
			end
		end
		if found > 0 then
			rev.p = rev.p + 1
			refresh()
		end
		return line("R", id, 1, "OK", "Explored.", "before=0", "after=" .. found)
	elseif op == "improve" then
		-- amount: one of the game's builds (farm, mine, road, clear forest ...), checked as in the menu.
		local plot = Map.GetPlot(x, y)
		local row = GameInfo.Builds and GameInfo.Builds[amount]
		local what, value, remove = nil, nil, false
		if plot and row then
			what, value, remove = work(plot, row)
		end
		if not what then
			return line("R", id, 0, "CANNOT", "That cannot be built here.")
		end
		if remove then
			plot:SetFeatureType(FeatureTypes.NO_FEATURE, -1)
		end
		if what == "imp" then
			plot:SetImprovementType(value)
		elseif what == "route" then
			plot:SetRouteType(value)
		else
			-- Clearing gives its timber to the nearest of the viewer's cities, as in the game.
			local nearest, best = nil, 5
			for city in player:Cities() do
				local d = Map.PlotDistance(x, y, city:GetX(), city:GetY())
				if d < best then
					nearest, best = city, d
				end
			end
			if nearest and value > 0 and production(nearest) ~= "" then
				nearest:ChangeProduction(value)
				rev.c = rev.c + 1
			end
		end
		rev.p = rev.p + 1
		refresh()
		return line("R", id, 1, "OK", "Done.", "kind=" .. what)
	elseif op == "gift" then
		-- A gift of gold to a city-state, in one of the three sizes the game itself offers.
		local minor = Players[entity]
		if not minor or not minor:IsAlive() or not minor:IsMinorCiv() or not Teams[team]:IsHasMet(minor:GetTeam()) then
			return line("R", id, 0, "NO_MINOR", "That is not a city-state you know.")
		end
		if amount ~= GameDefines.MINOR_GOLD_GIFT_SMALL and amount ~= GameDefines.MINOR_GOLD_GIFT_MEDIUM and amount ~= GameDefines.MINOR_GOLD_GIFT_LARGE then
			return line("R", id, 0, "BAD_AMOUNT", "That is not a gift the game offers.")
		end
		if player:GetGold() < amount then
			return line("R", id, 0, "NO_GOLD", "Your treasury does not hold " .. amount .. " gold.")
		end
		local before = minor:GetMinorCivFriendshipWithMajor(ap)
		Game.DoMinorGoldGift(entity, amount)
		rev.v = rev.v + 1
		return line("R", id, 1, "OK", "Gift sent.", "before=" .. before, "after=" .. (before + 1))
	elseif op == "policy" then
		-- x: 5 open a branch of the policy tree, 6 adopt a policy. y: which. As the policy screen does it.
		local can = false
		if x == 5 then
			can = not try(player, "IsPolicyBranchUnlocked", false, y) and try(player, "CanUnlockPolicyBranch", false, y)
		elseif x == 6 then
			can = not try(player, "HasPolicy", false, y) and try(player, "CanAdoptPolicy", false, y)
		end
		if not can then
			return line("R", id, 0, "CANNOT", "That policy cannot be adopted right now.")
		end
		local before = try(player, "GetNumPolicies", 0) + try(player, "GetNumPolicyBranchesUnlocked", 0)
		Network.SendUpdatePolicies(y, x == 6, true)
		rev.v = rev.v + 1
		return line("R", id, 1, "OK", "Policy adopted.", "before=" .. before, "after=" .. (before + 1))
	elseif op == "war" then
		local other = Players[entity]
		if not other or not other:IsAlive() or other:IsMinorCiv() or entity == ap then
			return line("R", id, 0, "NO_CIV", "That is not a civilization you can declare war on.")
		end
		if Teams[team]:IsAtWar(other:GetTeam()) then
			return line("R", id, 0, "AT_WAR", "You are already at war.")
		end
		if not Teams[team]:CanDeclareWar(other:GetTeam()) then
			return line("R", id, 0, "CANNOT", "You cannot declare war on them right now.")
		end
		Network.SendChangeWar(other:GetTeam(), true)
		rev.v = rev.v + 1
		return line("R", id, 1, "OK", "War declared.")
	end
	return line("R", id, 0, "UNKNOWN_OP", "Unknown operation.")
end

function LW.cmd(seq, id, op, owner, entity, x, y, amount)
	print(P .. ">\t" .. seq)
	local answer = LW.done[id]
	if not answer then
		local ok, r = pcall(apply, id, op, owner, entity, x, y, amount)
		answer = ok and r or line("R", id, 0, "ERROR", r)
		-- Remember the answer: the same request arriving twice gets it again and changes nothing.
		if LW.doneCount >= 200 then
			LW.done = {}
			LW.doneCount = 0
		end
		LW.done[id] = answer
		LW.doneCount = LW.doneCount + 1
	end
	print(heartbeat())
	print(answer)
	print(P .. "<\t" .. seq .. "\t2")
end

function LW.shutdown()
	for i = 1, #hooks do
		pcall(hooks[i][1].Remove, hooks[i][2])
	end
	hooks = {}
	if marker then
		pcall(function()
			Controls.SettlerReccomendationStore:ReleaseChild(marker.Anchor)
		end)
		marker = nil
	end
end

LivingWorld = LW
