package forge.game.ability.effects;

import forge.game.GameAction;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;

import java.util.HashMap;

public class ClashEffect extends SpellAbilityEffect {

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellEffect#getStackDescription(java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected String getStackDescription(final SpellAbility sa) {
        return sa.getHostCard().getName() + " - Clash with an opponent.";
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellEffect#resolve(java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    public void resolve(final SpellAbility sa) {
        final boolean victory = clashWithOpponent(sa);

        // Run triggers
        final HashMap<String, Object> runParams = new HashMap<String, Object>();
        runParams.put("Player", sa.getHostCard().getController());

        if (victory) {

            AbilitySub sub = sa.getAdditionalAbility("WinSubAbility");
            if (sub != null) {
                AbilityUtils.resolve(sub);
            }

            runParams.put("Won", "True");
        } else {
            AbilitySub sub = sa.getAdditionalAbility("OtherwiseSubAbility");
            if (sub != null) {
                AbilityUtils.resolve(sub);
            }

            runParams.put("Won", "False");
        }

        
        sa.getHostCard().getGame().getTriggerHandler().runTrigger(TriggerType.Clashed, runParams, false);
    }

    /**
     * <p>
     * clashWithOpponent.
     * </p>
     * 
     * @param source
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    private static boolean clashWithOpponent(final SpellAbility sa) {
        /*
         * Each clashing player reveals the top card of his or her library, then
         * puts that card on the top or bottom. A player wins if his or her card
         * had a higher mana cost.
         * 
         * Clash you win or win you don't. There is no tie.
         */
        final Card source = sa.getHostCard();
        final Player player = source.getController();
        final Player opponent = sa.getActivatingPlayer().getController().chooseSingleEntityForEffect(player.getOpponents(), sa, "Choose a opponent") ;
        final ZoneType lib = ZoneType.Library;

        if (sa.hasParam("RememberClasher")) {
            source.addRemembered(opponent);
        }
    
        final PlayerZone pLib = player.getZone(lib);
        final PlayerZone oLib = opponent.getZone(lib);
    
        final StringBuilder reveal = new StringBuilder();
    
        Card pCard = null;
        Card oCard = null;
    
        if (pLib.size() > 0) {
            pCard = pLib.get(0);
            player.getGame().getAction().reveal(new CardCollection(pCard), pCard.getOwner(), false);
        }
        if (oLib.size() > 0) {
            oCard = oLib.get(0);
            player.getGame().getAction().reveal(new CardCollection(oCard), oCard.getOwner(), false);
        }
    
        if ((pLib.size() == 0) && (oLib.size() == 0)) {
            return false;
        } else if (pLib.isEmpty()) {
            clashMoveToTopOrBottom(opponent, oCard, sa);
            return false;
        } else if (oLib.isEmpty()) {
            clashMoveToTopOrBottom(player, pCard, sa);
            return true;
        } else {
            final int pCMC = pCard.getCMC();
            final int oCMC = oCard.getCMC();
            
            // TODO: Split cards will return two CMC values, so both players may become winners of clash
            
            reveal.append(player).append(" reveals: ").append(pCard.getName()).append(".  CMC = ").append(pCMC);
            reveal.append("\r\n");
            reveal.append(opponent).append(" reveals: ").append(oCard.getName()).append(".  CMC = ").append(oCMC);
            reveal.append("\r\n\r\n");
            reveal.append(player).append(pCMC > oCMC ? " wins clash." : " loses clash.");
            
            player.getGame().getAction().nofityOfValue(sa, source, reveal.toString(), null);
            clashMoveToTopOrBottom(player, pCard, sa);
            clashMoveToTopOrBottom(opponent, oCard, sa);
            return pCMC > oCMC;
        }
    }

    private static void clashMoveToTopOrBottom(final Player p, final Card c, final SpellAbility sa) {
        final GameAction action = p.getGame().getAction();
        final boolean putOnTop = p.getController().willPutCardOnTop(c);
        if (putOnTop) {
            action.moveToLibrary(c, sa);
        } else {
            action.moveToBottomOfLibrary(c, sa);
        }
        // computer just puts the card back until such time it can make a smarter decision
    }
}
