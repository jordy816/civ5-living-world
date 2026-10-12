-- Test setup in one go, from the load screen: begin, found the capital, wound and fortify the Warrior.
Events.LoadScreenClose()
Game.SetPausePlayer(-1)
UI.SetDontShowPopups(false)
local p = Players[Game.GetActivePlayer()]
local settler, warrior
for u in p:Units() do
	if u:IsFound() then settler = u elseif u:IsCombatUnit() then warrior = u end
end
if settler then
	local x, y = settler:GetX(), settler:GetY()
	p:InitCity(x, y)
	settler:Kill(true, -1)
	print("city founded at", x, y)
end
if warrior then
	warrior:SetDamage(40)
	UI.SelectUnit(warrior)
	Game.SelectionListGameNetMessage(GameMessageTypes.GAMEMESSAGE_PUSH_MISSION, MissionTypes.MISSION_FORTIFY, 0, 0, 0, false, false)
	print("warrior", warrior:GetID(), "hp", warrior:GetCurrHitPoints(), "at", warrior:GetX(), warrior:GetY())
end
local c = p:GetCapitalCity()
print("cities", p:GetNumCities(), c and c:GetName() or "none", "turn", Game.GetGameTurn())
