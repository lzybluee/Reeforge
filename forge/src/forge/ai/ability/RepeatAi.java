package forge.ai.ability;


import forge.ai.AiController;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;

public class RepeatAi extends SpellAbilityAi {

    @Override
    protected boolean canPlayAI(Player ai, SpellAbility sa) {
    	final Card source = sa.getHostCard();
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final Player opp = ComputerUtil.getOpponentFor(ai);

        if (tgt != null) {
            if (!opp.canBeTargetedBy(sa)) {
                return false;
            }
            sa.resetTargets();
            sa.getTargets().add(opp);
        }
        String logic = sa.getParam("AILogic");
        if ("MaxX".equals(logic)) {
            // Set PayX here to maximum value.
            final int max = ComputerUtilMana.determineLeftoverMana(sa, ai);
            source.setSVar("PayX", Integer.toString(max));
            if (max <= 0) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message) {
      //TODO add logic to have computer make better choice (ArsenalNut)
        return false;
    }

    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
    	
    	 if (sa.usesTargeting()) {
         	final Player opp = ComputerUtil.getOpponentFor(ai);
             if (sa.canTarget(opp)) {
                 sa.resetTargets();
                 sa.getTargets().add(opp);
             } else if (!mandatory) {
            	 return false;
             }

         }

    	// setup subability to repeat
        final SpellAbility repeat = sa.getAdditonalAbility("RepeatSubAbility");

        if (repeat == null) {
        	return false;
        }

        AiController aic = ((PlayerControllerAi)ai.getController()).getAi();
        return aic.doTrigger(repeat, mandatory);
    }
}
