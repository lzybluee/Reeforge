package forge.game.ability.effects;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterType;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.TextUtil;

import java.util.Iterator;
import java.util.List;

public class SetStateEffect extends SpellAbilityEffect {

    @Override
    protected String getStackDescription(final SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();

        final List<Card> tgtCards = getTargetCards(sa);

        if (sa.hasParam("Flip")) {
            sb.append("Flip");
        } else {
            sb.append("Transform ");
        }

        final Iterator<Card> it = tgtCards.iterator();
        while (it.hasNext()) {
            sb.append(it.next());

            if (it.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append(".");
        return sb.toString();
    }

    @Override
    public void resolve(final SpellAbility sa) {
        final Player p = sa.getActivatingPlayer();
        final String mode = sa.getParam("Mode");
        final Card host = sa.getHostCard();
        final Game game = host.getGame();
        final List<Card> tgtCards = getTargetCards(sa);

        final boolean remChanged = sa.hasParam("RememberChanged");
        final boolean morphUp = sa.hasParam("MorphUp");
        final boolean manifestUp = sa.hasParam("ManifestUp");
        final boolean hiddenAgenda = sa.hasParam("HiddenAgenda");
        final boolean optional = sa.hasParam("Optional");

        for (final Card tgt : tgtCards) {
            if (sa.usesTargeting() && !tgt.canBeTargetedBy(sa)) {
                continue;
            }

            // Cards which are not on the battlefield should not be able to transform.
            // TurnFace should be allowed in other zones like Exil too
            if (!"TurnFace".equals(mode) && !tgt.isInZone(ZoneType.Battlefield)) {
                continue;
            }

            // facedown cards that are not Permanent, can't turn faceup there
            if ("TurnFace".equals(mode) && tgt.isFaceDown() && tgt.isInZone(ZoneType.Battlefield)
                && !tgt.getState(CardStateName.Original).getType().isPermanent()) {
                // need to cache manifest status
                boolean manifested = tgt.isManifested();
                // FIXME setState has to many other Consequences, use LKI?
                tgt.setState(CardStateName.Original, true);
                game.getAction().reveal(new CardCollection(tgt), tgt.getOwner(), true, "Face-down card can't turn face up");
                tgt.setState(CardStateName.FaceDown, true);
                tgt.setManifested(manifested);

                continue;
            }

            // for reasons it can't transform, skip
            if ("Transform".equals(mode) && !tgt.canTransform()) {
                continue;
            }

            if ("Transform".equals(mode) && tgt.equals(host) && sa.hasSVar("StoredTransform")) {
                // If want to Transform, and host is trying to transform self, skip if not in alignment
                boolean skip = tgt.getTransformedTimestamp() != Long.parseLong(sa.getSVar("StoredTransform"));
                // Clear SVar from SA so it doesn't get reused accidentally
                sa.getSVars().remove("StoredTransform");
                if (skip) {
                    continue;
                }
            }

            if (optional) {
                String message = TextUtil.concatWithSpace("Transform", host.getName(), "?");
                if (!p.getController().confirmAction(sa, PlayerActionConfirmMode.Random, message)) {
                    return;
                }
            }

            boolean hasTransformed = false;
            if (morphUp) {
                hasTransformed = tgt.turnFaceUp();
            } else if (manifestUp) {
                hasTransformed = tgt.turnFaceUp(true, true);
            } else {
                hasTransformed = tgt.changeCardState(mode, sa.getParam("NewState"));
            }
            if ( hasTransformed ) {
                if (morphUp) {
                    String sb = p + " has unmorphed " + tgt.getName();
                    game.getGameLog().add(GameLogEntryType.STACK_RESOLVE, sb);
                } else if (manifestUp) {
                    String sb = p + " has unmanifested " + tgt.getName();
                    game.getGameLog().add(GameLogEntryType.STACK_RESOLVE, sb);
                } else if (hiddenAgenda) {
                    String sb = p + " has revealed " + tgt.getName() + " with the chosen name " + tgt.getNamedCard();
                    game.getGameLog().add(GameLogEntryType.STACK_RESOLVE, sb);
                }
                game.fireEvent(new GameEventCardStatsChanged(tgt));
                if (sa.hasParam("Mega")) {
                    tgt.addCounter(CounterType.P1P1, 1, host, true);
                }
                if (remChanged) {
                    host.addRemembered(tgt);
                }
            }
        }
    }
}
