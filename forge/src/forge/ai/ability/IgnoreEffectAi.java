package forge.ai.ability;

import java.util.List;

import forge.ai.SpellAbilityAi;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;

public class IgnoreEffectAi extends SpellAbilityAi {
	
	private boolean isSearchAbility(Player aiPlayer, SpellAbility sa, SpellAbility spell) {
		if(sa == null || spell.getActivatingPlayer() == null) {
			return false;
		}
		if(sa.getApi() != ApiType.ChangeZone && sa.getApi() != ApiType.ChangeZoneAll) {
			return false;
		}
		final String origin = sa.hasParam("Origin") ? sa.getParam("Origin").toLowerCase() : null;
		if(origin == null || !origin.equals("library")) {
			return false;
		}
		final String destination = sa.hasParam("Destination") ? sa.getParam("Destination").toLowerCase() : null;
		if(destination == null || !(destination.equals("battlefield") || destination.equals("hand"))) {
			return false;
		}
		String definedPlayer = sa.hasParam("DefinedPlayer") ? sa.getParam("DefinedPlayer") : null;
		if(definedPlayer == null) {
			definedPlayer = sa.hasParam("DefinedPlayers") ? sa.getParam("DefinedPlayers") : null;
		}
		final boolean hasDefined = definedPlayer != null && 
				(definedPlayer.equals("RememberedController") || definedPlayer.equals("TargetedController") || definedPlayer.equals("Player"));
		if(hasDefined) {
			final TargetRestrictions tgt = spell.getTargetRestrictions();
			if(tgt != null) {
				List<GameObject> objects = spell.getTargets().getTargets();
	            for (Object o : objects) {
	                if (o instanceof Card) {
	                    Card card = (Card)o;
	                    if(aiPlayer.equals(card.getController())) {
	                    	return true;
	                    }
	                } else if (o instanceof Player) {
	                	if(aiPlayer.equals(o)) {
	                		return true;
	                	}
	                }
	            }
			}
			return false;
		}
		return aiPlayer.equals(spell.getActivatingPlayer());
	}

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#canPlayAI(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected boolean canPlayAI(Player aiPlayer, SpellAbility sa) {
		if(aiPlayer.hasKeyword("CantSearchLibrary") && sa.getHostCard() != null && sa.getHostCard().getName().equals("Leonin Arbiter")) {
			Game game = aiPlayer.getGame();
			boolean search = false;
			if(!game.getStack().isEmpty()) {
		        for (SpellAbilityStackInstance si : game.getStack()) {
		        	SpellAbility spell = si.getSpellAbility(true);
		        	if(isSearchAbility(aiPlayer, spell, spell)) {
		        		search = true;
		        		break;
		        	}
		        	SpellAbility sub = spell.getSubAbility();
		        	if(isSearchAbility(aiPlayer, sub, spell)) {
		        		search = true;
		        		break;
		        	}
		            while (sub != null && sub != sa) {
		                sub = sub.getSubAbility();
		                if(isSearchAbility(aiPlayer, sub, spell)) {
			        		search = true;
			        		break;
			        	}
		            }
		            if(search) {
		            	break;
		            }
		        }
		        return search;
			}
		}
        return false;
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#chkAIDrawback(java.util.Map, forge.card.spellability.SpellAbility, forge.game.player.Player)
     */
    @Override
    public boolean chkAIDrawback(SpellAbility sa, Player aiPlayer) {
        return canPlayAI(aiPlayer, sa);
    }
}
