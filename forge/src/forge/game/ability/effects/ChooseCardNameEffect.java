package forge.game.ability.effects;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import forge.StaticData;
import forge.card.CardFacePredicates;
import forge.card.CardRules;
import forge.card.CardRulesPredicates;
import forge.card.CardSplitType;
import forge.card.ICardFace;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.item.PaperCard;
import forge.util.Aggregates;
import forge.util.ComparableOp;

import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class ChooseCardNameEffect extends SpellAbilityEffect {

    @Override
    protected String getStackDescription(SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();

        for (final Player p : getTargetPlayers(sa)) {
            sb.append(p).append(" ");
        }
        sb.append("names a card.");

        return sb.toString();
    }

    @Override
    public void resolve(SpellAbility sa) {
        final Card host = sa.getHostCard();

        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final List<Player> tgtPlayers = getTargetPlayers(sa);

        String valid = "Card";
        String validDesc = "card";
        if (sa.hasParam("ValidCards")) {
            valid = sa.getParam("ValidCards");
            validDesc = sa.getParam("ValidDesc");
        }

        boolean randomChoice = sa.hasParam("AtRandom");
        boolean chooseFromDefined = sa.hasParam("ChooseFromDefinedCards");
        for (final Player p : tgtPlayers) {
            if ((tgt == null) || p.canBeTargetedBy(sa)) {
                String chosen = "";

                if (randomChoice) {
                    // Currently only used for Momir Avatar, if something else gets added here, make it more generic

                    String numericAmount = "X";
                    final int validAmount = StringUtils.isNumeric(numericAmount) ? Integer.parseInt(numericAmount) :
                        AbilityUtils.calculateAmount(host, numericAmount, sa);

                    // Momir needs PaperCard
                    Collection<PaperCard> cards = StaticData.instance().getCommonCards().getUniqueCards();
                    Predicate<PaperCard> cpp = Predicates.and(
                        Predicates.compose(CardRulesPredicates.Presets.IS_CREATURE, PaperCard.FN_GET_RULES),
                        Predicates.compose(CardRulesPredicates.cmc(ComparableOp.EQUALS, validAmount), PaperCard.FN_GET_RULES)
                    );

                    cards = Lists.newArrayList(Iterables.filter(cards, cpp));
                    if (!cards.isEmpty()) {
                        chosen = Aggregates.random(cards).getName();
                    } else {
                        chosen = "";
                    }
                } else if (chooseFromDefined) {
                    CardCollection choices = AbilityUtils.getDefinedCards(host, sa.getParam("ChooseFromDefinedCards"), sa);
                    choices = CardLists.getValidCards(choices, valid, host.getController(), host);
                    List<ICardFace> faces = Lists.newArrayList();
                    // get Card
                    for (final Card c : choices) {
                        final CardRules rules = c.getRules();
                        if (faces.contains(rules.getMainPart()))
                            continue;
                        faces.add(rules.getMainPart());
                        // Alhammarret only allows Split for other faces
                        if (rules.getSplitType() == CardSplitType.Split) {
                            faces.add(rules.getOtherPart());
                        }
                    }
                    Collections.sort(faces);
                    chosen = p.getController().chooseCardName(sa, faces, "Choose a card name");
                } else {
                    // use CardFace because you might name a alternate name
                    final String message = validDesc.equals("card") ? "Name a card" : "Name a " + validDesc + " card.";

                    Predicate<ICardFace> cpp = Predicates.alwaysTrue();
                    if ( StringUtils.containsIgnoreCase(valid, "nonland") ) {
                        cpp = CardFacePredicates.Presets.IS_NON_LAND;
                    }
                    if ( StringUtils.containsIgnoreCase(valid, "nonbasic") ) {
                        cpp = Predicates.not(CardFacePredicates.Presets.IS_BASIC_LAND);
                    }

                    if ( StringUtils.containsIgnoreCase(valid, "noncreature") ) {
                        cpp = Predicates.not(CardFacePredicates.Presets.IS_CREATURE);
                    } else if ( StringUtils.containsIgnoreCase(valid, "creature") ) {
                        cpp = CardFacePredicates.Presets.IS_CREATURE;
                    }

                    chosen = p.getController().chooseCardName(sa, cpp, valid, message);
                }

                host.setNamedCard(chosen);
                if(!randomChoice) {
                    p.getGame().getAction().nofityOfValue(sa, host, p.getName() + " picked " + chosen, p);
                    p.setNamedCard(chosen);
                }
            }
        }
    }

}
