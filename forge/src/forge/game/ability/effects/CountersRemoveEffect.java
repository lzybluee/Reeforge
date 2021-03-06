package forge.game.ability.effects;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerController;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

public class CountersRemoveEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();

        final String counterName = sa.getParam("CounterType");
        final String num = sa.getParam("CounterNum");

        int amount = 0;
        if (!num.equals("All") && !num.equals("Remembered")) {
            amount = AbilityUtils.calculateAmount(sa.getHostCard(), num, sa);
        };

        sb.append("Remove ");
        if (sa.hasParam("UpTo")) {
            sb.append("up to ");
        }
        if ("All".matches(counterName)) {
            sb.append("all counter");
        } else if ("Any".matches(counterName)) {
            if (amount == 1) {
                sb.append("a counter");
            } else {
                sb.append(amount).append(" ").append(" counter");
            }
        } else {
            sb.append(amount).append(" ").append(CounterType.valueOf(counterName).getName()).append(" counter");
        }
        if (amount != 1) {
            sb.append("s");
        }
        sb.append(" from");

        for (final Card c : getTargetCards(sa)) {
            sb.append(" ").append(c);
        }

        for (final Player tgtPlayer : getTargetPlayers(sa)) {
            sb.append(" ").append(tgtPlayer);
        }

        sb.append(".");

        return sb.toString();
    }

    @Override
    public void resolve(SpellAbility sa) {

        final Card card = sa.getHostCard();
        final Game game = card.getGame();
        final Player player = sa.getActivatingPlayer();

        PlayerController pc = player.getController();
        final String type = sa.getParam("CounterType");
        final String num = sa.getParam("CounterNum");

        int cntToRemove = 0;
        if (!num.equals("All") && !num.equals("Any") && !num.equals("Remembered")) {
            cntToRemove = AbilityUtils.calculateAmount(sa.getHostCard(), num, sa);
        }

        if (sa.hasParam("Optional")) {
            String ctrs = cntToRemove > 1 ? "counters" : num.equals("All") ? "all counters" : "a counter";
            if (!sa.getActivatingPlayer().getController().confirmAction(sa, null, "Remove " + ctrs + "?")) {
                return;
            }
        }

        CounterType counterType = null;

        if (!type.equals("Any") && !type.equals("All")) {
            try {
                counterType = AbilityUtils.getCounterType(type, sa);
            } catch (Exception e) {
                System.out.println("Counter type doesn't match, nor does an SVar exist with the type name.");
                return;
            }
        }

        boolean rememberRemoved = sa.hasParam("RememberRemoved");
        boolean rememberAmount = sa.hasParam("RememberAmount");

        for (final Player tgtPlayer : getTargetPlayers(sa)) {
            // Removing energy
            if (!sa.usesTargeting() || tgtPlayer.canBeTargetedBy(sa)) {
            	if (type.equals("All")) {
                    for (Map.Entry<CounterType, Integer> e : tgtPlayer.getCounters().entrySet()) {
                    	tgtPlayer.subtractCounter(e.getKey(), e.getValue());
                    }
                    continue;
                }
                if (num.equals("All")) {
                    cntToRemove = tgtPlayer.getCounters(counterType);
                }
                tgtPlayer.subtractCounter(counterType, cntToRemove);
            }
        }

        CardCollectionView srcCards = null;
        if (sa.hasParam("ValidSource")) {
            srcCards = game.getCardsIn(ZoneType.Battlefield);
            srcCards = CardLists.getValidCards(srcCards, sa.getParam("ValidSource"), player, card, sa);
            if (num.equals("Any") && !srcCards.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Choose cards to take ").append(counterType.getName()).append(" counters from");
                CardCollectionView chosen = new CardCollection();
                while(true) {
                	chosen = player.getController().chooseCardsForEffect(srcCards, sa, sb.toString(), 0, srcCards.size(), true);
                	if(chosen != null && chosen.isEmpty() && srcCards.size() > 0) {
                    	if (player.getController().confirmAction(sa, PlayerActionConfirmMode.OptionalChoose, "Cancel Choose?")) {
                            break;
                        }
                    } else {
                    	break;
                    }
                }
            }
        } else {
            srcCards = getTargetCards(sa);
        }

        int totalRemoved = 0;

        for (final Card tgtCard : srcCards) {
            Card gameCard = game.getCardState(tgtCard, null);
            // gameCard is LKI in that case, the card is not in game anymore
            // or the timestamp did change
            // this should check Self too
            if (gameCard == null || !tgtCard.equalsWithTimestamp(gameCard)) {
                continue;
            }
            if (!sa.usesTargeting() || tgtCard.canBeTargetedBy(sa)) {
                final Zone zone = game.getZoneOf(gameCard);
                if (type.equals("All")) {
                    for (Map.Entry<CounterType, Integer> e : tgtCard.getCounters().entrySet()) {
                        tgtCard.subtractCounter(e.getKey(), e.getValue());
                    }
                    game.updateLastStateForCard(tgtCard);
                    continue;
                } else if (num.equals("All") || num.equals("Any")) {
                    cntToRemove = gameCard.getCounters(counterType);
                } else if (num.equals("Remembered")) {
                    cntToRemove = gameCard.getCountersAddedBy(card, counterType);
                }
                
                if (type.equals("Any")) {
                    while (cntToRemove > 0 && tgtCard.hasCounters()) {
                        final Map<CounterType, Integer> tgtCounters = tgtCard.getCounters();
                        Map<String, Object> params = Maps.newHashMap();
                        params.put("Target", tgtCard);
                        
                        String prompt = "Select type of counters to remove from " + tgtCard;
                        CounterType chosenType = pc.chooseCounterType(
                                ImmutableList.copyOf(tgtCounters.keySet()), sa, prompt, params);
                        prompt = "Select the number of " + chosenType.getName() + " counters to remove from " + tgtCard;
                        int max = Math.min(cntToRemove, tgtCounters.get(chosenType));
                        params = Maps.newHashMap();
                        params.put("Target", tgtCard);
                        params.put("CounterType", chosenType);
                        int chosenAmount = pc.chooseNumber(sa, prompt, 1, max, params);

                        if (chosenAmount > 0) {
                            tgtCard.subtractCounter(chosenType, chosenAmount);
                            game.updateLastStateForCard(tgtCard);
                            if (rememberRemoved) {
                                for (int i = 0; i < chosenAmount; i++) {
                                    card.addRemembered(Pair.of(chosenType, i));
                                }
                            }
                            cntToRemove -= chosenAmount;
                        }
                    }
                } else {
                    cntToRemove = Math.min(cntToRemove, tgtCard.getCounters(counterType));

                    if (zone.is(ZoneType.Battlefield) || zone.is(ZoneType.Exile)) {
                    	if (sa.hasParam("UpTo") || num.equals("Any")) {
                            Map<String, Object> params = Maps.newHashMap();
                            params.put("Target", tgtCard);
                            params.put("CounterType", type);
                            String title = "Select the number of " + type + " counters to remove from " + tgtCard;
                            cntToRemove = pc.chooseNumber(sa, title, 0, cntToRemove, params);
                        }
                            
                    }
                    if (cntToRemove > 0) {
                        tgtCard.subtractCounter(counterType, cntToRemove);
                        if (rememberRemoved) {
                            for (int i = 0; i < cntToRemove; i++) {
                                card.addRemembered(Pair.of(counterType, i));
                            }
                        }
                        game.updateLastStateForCard(tgtCard);
                        totalRemoved += cntToRemove;
                    }
                }
            }
        }
        
        if (totalRemoved > 0 && rememberAmount) {
            // TODO use SpellAbility Remember later
            card.addRemembered(Integer.valueOf(totalRemoved));
        }
    }

}
