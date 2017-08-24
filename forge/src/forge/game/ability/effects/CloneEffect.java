package forge.game.ability.effects;

import forge.GameCommand;
import forge.card.CardStateName;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CardFactory;
import forge.game.card.CardFactoryUtil;
import forge.game.card.CardLists;
import forge.game.card.CardUtil;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CloneEffect extends SpellAbilityEffect {
    // TODO update this method

    @Override
    protected String getStackDescription(SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();
        final Card host = sa.getHostCard();
        Card tgtCard = host;

        Card cardToCopy = host;
        if (sa.hasParam("Defined")) {
            List<Card> cloneSources = AbilityUtils.getDefinedCards(host, sa.getParam("Defined"), sa);
            if (!cloneSources.isEmpty()) {
                cardToCopy = cloneSources.get(0);
            }
        } else if (sa.usesTargeting()) {
            cardToCopy = sa.getTargets().getFirstTargetedCard();
        }

        List<Card> cloneTargets = AbilityUtils.getDefinedCards(host, sa.getParam("CloneTarget"), sa);
        if (!cloneTargets.isEmpty()) {
            tgtCard = cloneTargets.get(0);
        }

        sb.append(tgtCard);
        sb.append(" becomes a copy of " + cardToCopy + ".");

        return sb.toString();
    } // end cloneStackDescription()

    @Override
    public void resolve(SpellAbility sa) {
        final Card host = sa.getHostCard();
        final Player activator = sa.getActivatingPlayer();
        Card tgtCard = host;
        final Map<String, String> origSVars = host.getSVars();
        final Game game = activator.getGame();

        // find cloning source i.e. thing to be copied
        Card cardToCopy = null;
        
        if (sa.hasParam("Choices")) {
            CardCollectionView choices = game.getCardsIn(ZoneType.Battlefield);
            choices = CardLists.getValidCards(choices, sa.getParam("Choices"), activator, host);
            
            String title = sa.hasParam("ChoiceTitle") ? sa.getParam("ChoiceTitle") : "Choose a card ";
            cardToCopy = activator.getController().chooseSingleEntityForEffect(choices, sa, title, true);
        } else if (sa.hasParam("Defined")) {
            List<Card> cloneSources = AbilityUtils.getDefinedCards(host, sa.getParam("Defined"), sa);
            if (!cloneSources.isEmpty()) {
                cardToCopy = cloneSources.get(0);
            }
        } else if (sa.usesTargeting()) {
            cardToCopy = sa.getTargets().getFirstTargetedCard();
        }
        if (cardToCopy == null) {
            return;
        }

        final boolean optional = sa.hasParam("Optional");
        if (optional && !host.getController().getController().confirmAction(sa, null, "Do you want to copy " + cardToCopy + "?")) {
            return;
        }

        // find target of cloning i.e. card becoming a clone
        final List<Card> cloneTargets = AbilityUtils.getDefinedCards(host, sa.getParam("CloneTarget"), sa);
        if (!cloneTargets.isEmpty()) {
            tgtCard = cloneTargets.get(0);
            game.getTriggerHandler().clearInstrinsicActiveTriggers(tgtCard, null);
        }

        if (sa.hasParam("CloneZone")) {
            if (!tgtCard.getZone().is(ZoneType.smartValueOf(sa.getParam("CloneZone")))) {
                return;
            }
        }

        // determine the image to be used for the clone
        String imageFileName = cardToCopy.getGame().getRules().canCloneUseTargetsImage() ? tgtCard.getImageKey() : cardToCopy.getImageKey();
        if (sa.hasParam("ImageSource")) { // Allow the image to be stipulated by using a defined card source
            List<Card> cloneImgSources = AbilityUtils.getDefinedCards(host, sa.getParam("ImageSource"), sa);
            if (!cloneImgSources.isEmpty()) {
                imageFileName = cloneImgSources.get(0).getImageKey();
            }
        }

        final boolean keepName = sa.hasParam("KeepName");
        final String originalName = tgtCard.getName();
        final boolean copyingSelf = (tgtCard == cardToCopy);
        final boolean isTransformed = cardToCopy.getCurrentStateName() == CardStateName.Transformed || cardToCopy.getCurrentStateName() == CardStateName.Meld;
        final CardStateName origState = isTransformed || cardToCopy.isFaceDown() ? CardStateName.Original : cardToCopy.getCurrentStateName();

        if (!copyingSelf) {
            if (tgtCard.isCloned()) { // cloning again
                tgtCard.switchStates(CardStateName.Cloner, origState, false);
                tgtCard.setState(origState, false);
                tgtCard.clearStates(CardStateName.Cloner, false);
            }
            // add "Cloner" state to clone
            tgtCard.addAlternateState(CardStateName.Cloner, false);
            tgtCard.switchStates(origState, CardStateName.Cloner, false);
            tgtCard.setState(origState, false);
        } else {
            //copy Original state to Cloned
            tgtCard.addAlternateState(CardStateName.Cloned, false);
            tgtCard.switchStates(origState, CardStateName.Cloned, false);
            if (tgtCard.isFlipCard()) {
                tgtCard.setState(CardStateName.Original, false);
            }
        }

        CardFactory.copyCopiableCharacteristics(cardToCopy, tgtCard);

        // must copy abilities before first so cloned added abilities are handled correctly
        CardFactory.copyCopiableAbilities(cardToCopy, tgtCard);
        
        // add extra abilities as granted by the copy effect
        addExtraCharacteristics(tgtCard, sa, origSVars);

        // set the host card for copied replacement effects
        // needed for copied xPaid ETB effects (for the copy, xPaid = 0)
        for (final ReplacementEffect rep : tgtCard.getReplacementEffects()) {
            final SpellAbility newSa = rep.getOverridingAbility();
            if (newSa != null) {
                newSa.setOriginalHost(cardToCopy);
            }
        }

        // set the host card for copied spellabilities
        for (final SpellAbility newSa : tgtCard.getSpellAbilities()) {
            newSa.setHostCard(cardToCopy);
        }

        // restore name if it should be unchanged
        if (keepName) {
        	tgtCard.setName(originalName);
        }

        // If target is a flip card, also set characteristics of the flipped
        // state.
        if (cardToCopy.isFlipCard()) {
        	final CardState flippedState = tgtCard.getState(CardStateName.Flipped);
            if (keepName) {
                flippedState.setName(originalName);
            }
            //keep the Clone card image for the cloned card
            flippedState.setImageKey(imageFileName);
        }

        //Clean up copy of cloned state
        if (copyingSelf) {
            tgtCard.clearStates(CardStateName.Cloned, false);
        }

        //game.getTriggerHandler().registerActiveTrigger(tgtCard, false);

        //keep the Clone card image for the cloned card
        tgtCard.setImageKey(imageFileName);

        tgtCard.updateStateForView();

        //Clear Remembered and Imprint lists
        tgtCard.clearRemembered();
        tgtCard.clearImprintedCards();

        // check if clone is now an Aura that needs to be attached
        if (tgtCard.isAura()) {
            AttachEffect.attachAuraOnIndirectEnterBattlefield(tgtCard);
        }

        if (sa.hasParam("Duration")) {
            final Card cloneCard = tgtCard;
            final GameCommand unclone = new GameCommand() {
                private static final long serialVersionUID = -78375985476256279L;

                @Override
                public void run() {
                    if (cloneCard.isCloned()) {
                        cloneCard.setState(CardStateName.Cloner, false);
                        cloneCard.switchStates(CardStateName.Cloner, origState, false);
                        cloneCard.clearStates(CardStateName.Cloner, false);
                        cloneCard.updateStateForView();
                        game.fireEvent(new GameEventCardStatsChanged(cloneCard));
                    }
                }
            };

            final String duration = sa.getParam("Duration");
            if (duration.equals("UntilEndOfTurn")) {
                game.getEndOfTurn().addUntil(unclone);
            }
            else if (duration.equals("UntilYourNextTurn")) {
                game.getCleanup().addUntil(host.getController(), unclone);
            }
        }
        game.fireEvent(new GameEventCardStatsChanged(tgtCard));
    } // cloneResolve

    private static void addExtraCharacteristics(final Card tgtCard, final SpellAbility sa, final Map<String, String> origSVars) {
        // additional types to clone
        if (sa.hasParam("AddTypes")) {
            for (final String type : Arrays.asList(sa.getParam("AddTypes").split(","))) {
                tgtCard.addType(type);
            }
        }

        // triggers to add to clone
        if (sa.hasParam("AddTriggers")) {
            for (final String s : Arrays.asList(sa.getParam("AddTriggers").split(","))) {
                if (origSVars.containsKey(s)) {
                    final String actualTrigger = origSVars.get(s);
                    final Trigger parsedTrigger = TriggerHandler.parseTrigger(actualTrigger, tgtCard, true);
                    tgtCard.addTrigger(parsedTrigger);
                }
            }
        }

        // SVars to add to clone
        if (sa.hasParam("AddSVars")) {
            for (final String s : Arrays.asList(sa.getParam("AddSVars").split(","))) {
                if (origSVars.containsKey(s)) {
                    final String actualsVar = origSVars.get(s);
                    tgtCard.setSVar(s, actualsVar);
                }
            }
        }

        // abilities to add to clone
        if (sa.hasParam("AddAbilities")) {
            for (final String s : Arrays.asList(sa.getParam("AddAbilities").split(","))) {
                if (origSVars.containsKey(s)) {
                    final String actualAbility = origSVars.get(s);
                    final SpellAbility grantedAbility = AbilityFactory.getAbility(actualAbility, tgtCard);
                    tgtCard.addSpellAbility(grantedAbility);
                }
            }
        }

        // keywords to add to clone
        
        if (sa.hasParam("AddKeywords")) {
            final List<String> keywords = Arrays.asList(sa.getParam("AddKeywords").split(" & "));
            // allow SVar substitution for keywords
            for (int i = 0; i < keywords.size(); i++) {
                String k = keywords.get(i);
                if (origSVars.containsKey(k)) {
                    keywords.add("\"" + k + "\"");
                    keywords.remove(k);
                }
                k = keywords.get(i);
                tgtCard.addIntrinsicKeyword(k);

                CardFactoryUtil.addTriggerAbility(k, tgtCard, null);
                CardFactoryUtil.addReplacementEffect(k, tgtCard, null);
                CardFactoryUtil.addSpellAbility(k, tgtCard, null);
            }
        }

        // set ETB tapped of clone
        if (sa.hasParam("IntoPlayTapped")) {
            tgtCard.setTapped(true);
        }

        // set power of clone
        if (sa.hasParam("SetPower")) {
            String rhs = sa.getParam("SetPower");
            int power = Integer.MAX_VALUE;
            try {
                power = Integer.parseInt(rhs);
            } catch (final NumberFormatException e) {
                power = CardFactoryUtil.xCount(tgtCard, tgtCard.getSVar(rhs));
            }
            for (StaticAbility sta : tgtCard.getStaticAbilities()) {
                Map<String, String> params = sta.getMapParams();
                if (params.containsKey("CharacteristicDefining") && params.containsKey("SetPower"))
                    tgtCard.removeStaticAbility(sta);
            }
            tgtCard.setBasePower(power);
        }

        // set toughness of clone
        if (sa.hasParam("SetToughness")) {
            String rhs = sa.getParam("SetToughness");
            int toughness = Integer.MAX_VALUE;
            try {
                toughness = Integer.parseInt(rhs);
            } catch (final NumberFormatException e) {
                toughness = CardFactoryUtil.xCount(tgtCard, tgtCard.getSVar(rhs));
            }
            for (StaticAbility sta : tgtCard.getStaticAbilities()) {
                Map<String, String> params = sta.getMapParams();
                if (params.containsKey("CharacteristicDefining") && params.containsKey("SetToughness"))
                    tgtCard.removeStaticAbility(sta);
            }
            tgtCard.setBaseToughness(toughness);
        }

        // colors to be added or changed to
        String shortColors = "";
        if (sa.hasParam("Colors")) {
            final String colors = sa.getParam("Colors");
            if (colors.equals("ChosenColor")) {
                shortColors = CardUtil.getShortColorsString(tgtCard.getChosenColors());
            } else {
                shortColors = CardUtil.getShortColorsString(Arrays.asList(colors.split(",")));
            }
        }
        tgtCard.addColor(shortColors, !sa.hasParam("OverwriteColors"), tgtCard.getTimestamp());

        if (sa.hasParam("Embalm") && tgtCard.isEmbalmed()) {
            tgtCard.addType("Zombie");
            tgtCard.setColor(MagicColor.WHITE);
            tgtCard.setManaCost(ManaCost.NO_COST);
        }
        if (sa.hasParam("Eternalize") && tgtCard.isEternalized()) {
            tgtCard.addType("Zombie");
            tgtCard.setColor(MagicColor.BLACK);
            tgtCard.setManaCost(ManaCost.NO_COST);
            tgtCard.setBasePower(4);
            tgtCard.setBaseToughness(4);
        }
    }

}
