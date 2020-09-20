package forge.player;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.*;
import forge.FThreads;
import forge.GuiBase;
import forge.LobbyPlayer;
import forge.achievement.AchievementCollection;
import forge.ai.GameState;
import forge.assets.FSkinProp;
import forge.card.*;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.control.FControlGamePlayback;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.events.UiEventNextGameDecision;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.spellability.*;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.MagicStack;
import forge.game.zone.PlayerZone;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.interfaces.IDevModeCheats;
import forge.interfaces.IGameController;
import forge.interfaces.IGuiGame;
import forge.interfaces.IMacroSystem;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.match.NextGameDecision;
import forge.match.input.*;
import forge.model.FModel;
import forge.properties.ForgeConstants;
import forge.properties.ForgePreferences.FPref;
import forge.trackable.TrackableObject;
import forge.util.ITriggerEvent;
import forge.util.Lang;
import forge.util.MessageUtil;
import forge.util.TextUtil;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import forge.util.gui.SOptionPane;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

/**
 * A prototype for player controller class
 *
 * Handles phase skips for now.
 */
public class PlayerControllerHuman extends PlayerController implements IGameController {
    /**
     * Cards this player may look at right now, for example when searching a
     * library.
     */
    private boolean mayLookAtAllCards = false;
    private boolean disableAutoYields = false;

    private IGuiGame gui;

    protected final InputQueue inputQueue;
    protected final InputProxy inputProxy;

    private boolean isConcede;
    private static String lastStateFileName;
    
    public PlayerControllerHuman(final Game game0, final Player p, final LobbyPlayer lp) {
        super(game0, p, lp);
        inputProxy = new InputProxy(this);
        inputQueue = new InputQueue(game, inputProxy);
    }

    public PlayerControllerHuman(final Player p, final LobbyPlayer lp, final PlayerControllerHuman owner) {
        super(owner.getGame(), p, lp);
        gui = owner.gui;
        inputProxy = owner.inputProxy;
        inputQueue = owner.getInputQueue();
    }

    public final IGuiGame getGui() {
        return gui;
    }

    public final void setGui(final IGuiGame gui) {
        this.gui = gui;
    }

    public final InputQueue getInputQueue() {
        return inputQueue;
    }

    public InputProxy getInputProxy() {
        return inputProxy;
    }

    public PlayerView getLocalPlayerView() {
        return player == null ? null : player.getView();
    }

    public boolean getDisableAutoYields() {
        return disableAutoYields;
    }

    public void setDisableAutoYields(final boolean disableAutoYields0) {
        disableAutoYields = disableAutoYields0;
    }

    @Override
    public boolean mayLookAtAllCards() {
        return mayLookAtAllCards;
    }

    private final Set<Card> tempShownCards = Sets.newHashSet();

    public <T> void tempShow(final Iterable<T> objects) {
        for (final T t : objects) {
            // assume you may see any card passed through here
            if (t instanceof Card) {
                tempShowCard((Card) t);
            } else if (t instanceof CardView) {
                tempShowCard(game.getCard((CardView) t));
            }
        }
    }

    private void tempShowCard(final Card c) {
        if (c == null) {
            return;
        }
        tempShownCards.add(c);
        c.setMayLookAt(player, true, true);
    }

    private void tempShowCards(final Iterable<Card> cards) {
        if (mayLookAtAllCards) {
            return;
        } // no needed if this is set

        for (final Card c : cards) {
            tempShowCard(c);
        }
    }

    private void endTempShowCards() {
        if (tempShownCards.isEmpty()) {
            return;
        }

        for (final Card c : tempShownCards) {
            c.setMayLookAt(player, false, true);
        }
        tempShownCards.clear();
    }

    /**
     * Set this to {@code true} to enable this player to see all cards any other
     * player can see.
     *
     * @param mayLookAtAllCards
     *            the mayLookAtAllCards to set
     */
    public void setMayLookAtAllCards(final boolean mayLookAtAllCards) {
        this.mayLookAtAllCards = mayLookAtAllCards;
    }

    /**
     * Uses GUI to learn which spell the player (human in our case) would like
     * to play
     */
    @Override
    public SpellAbility getAbilityToPlay(final Card hostCard, final List<SpellAbility> abilities,
            final ITriggerEvent triggerEvent) {
        final SpellAbilityView resultView = getGui().getAbilityToPlay(CardView.get(hostCard),
                SpellAbilityView.getCollection(abilities), triggerEvent);
        return getGame().getSpellAbility(resultView);
    }

    @Override
    public void playSpellAbilityForFree(final SpellAbility copySA, final boolean mayChooseNewTargets) {
    	boolean chooseNewTargets = mayChooseNewTargets;
    	if(chooseNewTargets && FModel.getPreferences().getPrefBoolean(FPref.UI_SKIP_AUTO_PAY)) {
    		chooseNewTargets = InputConfirm.confirm(this, copySA, "Choose new targets?");
    	}
        HumanPlay.playSaWithoutPayingManaCost(this, player.getGame(), copySA, chooseNewTargets);
    }

    @Override
    public void playSpellAbilityNoStack(final SpellAbility effectSA, final boolean canSetupTargets) {
        HumanPlay.playSpellAbilityNoStack(this, player, effectSA, !canSetupTargets);
    }

    @Override
    public List<PaperCard> sideboard(final Deck deck, final GameRules gameRules) {
        CardPool sideboard = deck.get(DeckSection.Sideboard);
        if (sideboard == null) {
            // Use an empty cardpool instead of null for 75/0 sideboarding
            // scenario.
            sideboard = new CardPool();
        }

        final CardPool main = deck.get(DeckSection.Main);

        final int mainSize = main.countAll();
        final int sbSize = sideboard.countAll();
        final int combinedDeckSize = mainSize + sbSize;

        int deckMinMainSize = 0;
        if(gameRules.getAppliedVariants() != null) {
        	for(GameType type : gameRules.getAppliedVariants()) {
        		int min = type.getDeckFormat().getMainRange().getMinimum();
        		if(min > deckMinMainSize) {
        			deckMinMainSize = min;
        		}
        	}
        } else {
        	deckMinMainSize = gameRules.getGameType().getDeckFormat().getMainRange().getMinimum();
        }

        final int deckMinSize = Math.min(mainSize, deckMinMainSize);
        final Range<Integer> sbRange = gameRules.getGameType().getDeckFormat().getSideRange();
        // Limited doesn't have a sideboard max, so let the Main min take care
        // of things.
        final int sbMax = sbRange == null ? combinedDeckSize : sbRange.getMaximum();

        List<PaperCard> newMain = null;

        // Skip sideboard loop if there are no sideboarding opportunities
        if (sbSize == 0 && (mainSize == deckMinSize || (deckMinSize == 0 && (mainSize == 60 || mainSize == 40)))) {
            return null;
        }

        // conformance should not be checked here
        final boolean conform = FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY);
        do {
            if (newMain != null) {
                String errMsg;
                if (newMain.size() < deckMinSize) {
                    errMsg = TextUtil.concatNoSpace("Too few cards in your main deck (minimum ",
                            String.valueOf(deckMinSize), "), please make modifications to your deck again.");
                } else {
                    errMsg = TextUtil.concatNoSpace("Too many cards in your sideboard (maximum ",
                            String.valueOf(sbMax), "), please make modifications to your deck again.");
                }
                getGui().showErrorDialog(errMsg, "Invalid Deck");
            }
            // Sideboard rules have changed for M14, just need to consider min
            // maindeck and max sideboard sizes
            // No longer need 1:1 sideboarding in non-limited formats
            Object resp = getGui().sideboard(sideboard, main);
            if (resp instanceof List<?> &&
                    !((List) resp).isEmpty() &&
                    ((List) resp).get(0) instanceof PaperCard) {
                newMain = (List) resp;
            } else if (resp == null) {
                // if we got here, the user took too long to reply
                newMain = main.toFlatList();
            } else {
                System.err.println("PlayerControllerHuman.sideboard -- FAILED!");
                System.err.println("resp instanceof " + resp.getClass().toString());
                System.err.println("resp = " + resp.toString());
            }
        } while (conform && (newMain.size() < deckMinSize || combinedDeckSize - newMain.size() > sbMax));

        return newMain;
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(final Card attacker, final CardCollectionView blockers,
            final int damageDealt, final GameEntity defender, final boolean overrideOrder) {
        // Attacker is a poor name here, since the creature assigning damage
        // could just as easily be the blocker.
        final Map<Card, Integer> map = Maps.newHashMap();
        if (defender != null && assignDamageAsIfNotBlocked(attacker)) {
            map.put(null, damageDealt);
        } else {
            final List<CardView> vBlockers = CardView.getCollection(blockers);
            if ((attacker.hasKeyword(Keyword.TRAMPLE) && defender != null) || (blockers.size() > 1)) {
                final CardView vAttacker = CardView.get(attacker);
                final GameEntityView vDefender = GameEntityView.get(defender);
                final Map<CardView, Integer> result = getGui().assignDamage(vAttacker, vBlockers, damageDealt,
                        vDefender, overrideOrder);
                for (final Entry<CardView, Integer> e : result.entrySet()) {
                    map.put(game.getCard(e.getKey()), e.getValue());
                }
            } else {
                map.put(blockers.get(0), damageDealt);
            }
        }
        return map;
    }

    private final boolean assignDamageAsIfNotBlocked(final Card attacker) {
        if (attacker.hasKeyword("CARDNAME assigns its combat damage as though it weren't blocked.") || attacker
                .hasKeyword("You may have CARDNAME assign its combat damage as though it weren't blocked.")) {
            return InputConfirm.confirm(this, CardView.get(attacker),
                    "Do you want to assign its combat damage as though it weren't blocked?");
        } else {
            return false;
        }
    }

    @Override
    public Integer announceRequirements(final SpellAbility ability, final String announce,
            final boolean canChooseZero) {
        final int min = canChooseZero ? 0 : 1;
        return getGui().getInteger("Choose " + announce + " for " + ability.getHostCard().getName(), min,
                Integer.MAX_VALUE, min + 21);
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(final SpellAbility sa, final int min, final int max,
            final CardCollectionView valid, final String message) {
        return choosePermanentsTo(min, max, valid, message, "sacrifice", sa);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(final SpellAbility sa, final int min, final int max,
            final CardCollectionView valid, final String message) {
        return choosePermanentsTo(min, max, valid, message, "destroy", sa);
    }

    private CardCollectionView choosePermanentsTo(final int min, int max, final CardCollectionView valid,
            final String message, final String action, final SpellAbility sa) {
        max = Math.min(max, valid.size());
        if (max <= 0) {
            return CardCollection.EMPTY;
        }

        final StringBuilder builder = new StringBuilder("Select ");
        if (min == 0) {
            builder.append("up to ");
        }
        builder.append("%d " + message + "(s) to " + action + ".");

        CardCollection selected = new CardCollection();

        while(true) {
	        final InputSelectCardsFromList inp = new InputSelectCardsFromList(this, min, max, valid, sa);
	        inp.setMessage(builder.toString());
	        inp.setCancelAllowed(min == 0);
	        inp.showAndWait();
	        
	        if(!inp.hasCancelled() && !valid.isEmpty() && inp.getSelected().isEmpty() && min == 0 && max > 0) {
            	if(InputConfirm.confirm(this, sa, "Cancel " + action + " ?")) {
            		break;
            	}
	        } else {
	        	selected.addAll(inp.getSelected());
	        	break;
	        }
        }

        return selected;
    }

    @Override
    public CardCollectionView chooseCardsForEffect(final CardCollectionView sourceList, final SpellAbility sa,
            final String title, final int min, final int max, final boolean isOptional) {
        // If only one card to choose, use a dialog box.
        // Otherwise, use the order dialog to be able to grab multiple cards in
        // one shot

        if (max == 1) {
        	final Card singleChosen = chooseSingleEntityForEffect(sourceList, sa, title, isOptional);
            return singleChosen == null ? CardCollection.EMPTY : new CardCollection(singleChosen);
        }

        getGui().setPanelSelection(CardView.get(sa.getHostCard()));

        // try to use InputSelectCardsFromList when possible
        boolean cardsAreInMyHandOrBattlefield = true;
        for (final Card c : sourceList) {
            final Zone z = c.getZone();
            if (z != null && (z.is(ZoneType.Battlefield) || z.is(ZoneType.Hand, player))) {
                continue;
            }
            cardsAreInMyHandOrBattlefield = false;
            break;
        }

        if (cardsAreInMyHandOrBattlefield) {
            final InputSelectCardsFromList sc = new InputSelectCardsFromList(this, min, max, sourceList, sa);
            sc.setMessage(title);
            sc.setCancelAllowed(isOptional);
            sc.showAndWait();
            return new CardCollection(sc.getSelected());
        }

        tempShowCards(sourceList);
        final CardCollection choices = getGame().getCardList(getGui().many(title, "Chosen Cards", min, max,
                CardView.getCollection(sourceList), CardView.get(sa.getHostCard())));
        endTempShowCards();

        return choices;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(final FCollectionView<T> optionList,
            final DelayedReveal delayedReveal, final SpellAbility sa, final String title, final boolean isOptional,
            final Player targetedPlayer) {
        // Human is supposed to read the message and understand from it what to
        // choose
        if (optionList.isEmpty()) {
            if (delayedReveal != null) {
                reveal(delayedReveal.getCards(), delayedReveal.getZone(), delayedReveal.getOwner(),
                        delayedReveal.getMessagePrefix());
            }
            return null;
        }
        if (!isOptional && optionList.size() == 1) {
            if (delayedReveal != null) {
                reveal(delayedReveal.getCards(), delayedReveal.getZone(), delayedReveal.getOwner(),
                        delayedReveal.getMessagePrefix());
            }
            return Iterables.getFirst(optionList, null);
        }

        boolean canUseSelectCardsInput = true;
        for (final GameEntity c : optionList) {
            if (c instanceof Player) {
                continue;
            }
            final Zone cz = ((Card) c).getZone();
            // can point at cards in own hand and anyone's battlefield
            final boolean canUiPointAtCards = cz != null
                    && (cz.is(ZoneType.Hand) && cz.getPlayer() == player || cz.is(ZoneType.Battlefield));
            if (!canUiPointAtCards) {
                canUseSelectCardsInput = false;
                break;
            }
        }

        if (canUseSelectCardsInput) {
            if (delayedReveal != null) {
                reveal(delayedReveal.getCards(), delayedReveal.getZone(), delayedReveal.getOwner(),
                        delayedReveal.getMessagePrefix());
            }
            InputSelectEntitiesFromList<T> input = null;
            while(true) {
            	input = new InputSelectEntitiesFromList<T>(this, isOptional ? 0 : 1, 1,
	                    optionList, sa);
	            input.setCancelAllowed(isOptional);
	            input.setMessage(MessageUtil.formatMessage(title, player, targetedPlayer));
	            input.showAndWait();
	            if(!input.hasCancelled() && isOptional && !optionList.isEmpty() && input.getSelected().isEmpty()) {
	            	if(InputConfirm.confirm(this, sa, "Cancel?")) {
	            		break;
	            	}
	            } else {
	            	break;
	            }
            }
            return Iterables.getFirst(input.getSelected(), null);
        }

        tempShow(optionList);
        if (delayedReveal != null) {
            tempShow(delayedReveal.getCards());
        }
        final GameEntityView result = getGui().chooseSingleEntityForEffect(title,
                GameEntityView.getEntityCollection(optionList), delayedReveal, isOptional, 1);
        endTempShowCards();

        if (result instanceof CardView) {
            return (T) game.getCard((CardView) result);
        }
        if (result instanceof PlayerView) {
            return (T) game.getPlayer((PlayerView) result);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(final FCollectionView<T> optionList,
            final DelayedReveal delayedReveal, final SpellAbility sa, final String title, final Player targetedPlayer) {
        // Human is supposed to read the message and understand from it what to
        // choose
        if (optionList.isEmpty()) {
            if (delayedReveal != null) {
                reveal(delayedReveal.getCards(), delayedReveal.getZone(), delayedReveal.getOwner(),
                        delayedReveal.getMessagePrefix());
            }
            return Lists.newArrayList();
        }

        boolean canUseSelectCardsInput = true;
        for (final GameEntity c : optionList) {
            if (c instanceof Player) {
                continue;
            }
            final Zone cz = ((Card) c).getZone();
            // can point at cards in own hand and anyone's battlefield
            final boolean canUiPointAtCards = cz != null
                    && (cz.is(ZoneType.Hand) && cz.getPlayer() == player || cz.is(ZoneType.Battlefield));
            if (!canUiPointAtCards) {
                canUseSelectCardsInput = false;
                break;
            }
        }

        if (canUseSelectCardsInput) {
            if (delayedReveal != null) {
                reveal(delayedReveal.getCards(), delayedReveal.getZone(), delayedReveal.getOwner(),
                        delayedReveal.getMessagePrefix());
            }
            final InputSelectEntitiesFromList<T> input = new InputSelectEntitiesFromList<T>(this, 0, optionList.size(),
                    optionList, sa);
            input.setCancelAllowed(true);
            input.setMessage(MessageUtil.formatMessage(title, player, targetedPlayer));
            input.showAndWait();
            return (List<T>) input.getSelected();
        }

        tempShow(optionList);
        if (delayedReveal != null) {
            tempShow(delayedReveal.getCards());
        }
        final List<GameEntityView> chosen = getGui().chooseEntitiesForEffect(title,
                GameEntityView.getEntityCollection(optionList), delayedReveal);
        endTempShowCards();

        List<T> results = new ArrayList<>();
        if (chosen instanceof List && chosen.size() > 0) {
            for (GameEntityView entry: chosen) {
                if (entry instanceof CardView) {
                    results.add((T)game.getCard((CardView) entry));
                }
                if (entry instanceof PlayerView) {
                    results.add((T)game.getPlayer((PlayerView) entry));
                }
            }
        }
        return results;
    }

    @Override
    public int chooseNumber(final SpellAbility sa, final String title, final int min, final int max) {
        if (min >= max) {
            return min;
        }
        final ImmutableList.Builder<Integer> choices = ImmutableList.builder();
        for (int i = 0; i <= max - min; i++) {
            choices.add(Integer.valueOf(i + min));
        }
        return getGui().one(title, choices.build()).intValue();
    }

    @Override
    public int chooseNumber(final SpellAbility sa, final String title, final List<Integer> choices,
            final Player relatedPlayer) {
        return getGui().one(title, choices).intValue();
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(final List<SpellAbility> spells, final SpellAbility sa,
            final String title) {
        if (spells.size() < 2) {
            return Iterables.getFirst(spells, null);
        }

        // Show the card that asked for this choice
        getGui().setPaperCard(CardView.get(sa.getHostCard()));

        // create a mapping between a spell's view and the spell itself
        HashMap<SpellAbilityView, SpellAbility> spellViewCache = new LinkedHashMap<>();
        for (SpellAbility spellAbility : spells) {
            spellViewCache.put(spellAbility.getView(), spellAbility);
        }
        List<TrackableObject> choices = new ArrayList<>();
        choices.addAll(spellViewCache.keySet());
        Object choice = getGui().one(title, choices);

        // Human is supposed to read the message and understand from it what to
        // choose
        return spellViewCache.get(choice);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.game.player.PlayerController#confirmAction(forge.card.spellability.
     * SpellAbility, java.lang.String, java.lang.String)
     */
    @Override
    public boolean confirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode, final String message) {
    	if (sa != null && sa.getApi() == ApiType.SetState) {
    		final String cardName = sa.getHostCard() == null ? null : sa.getHostCard().getName();
            if (getGui().shouldAutoYieldCard(cardName)) {
        		return false;
            }
    	}
        if (sa != null && sa.getHostCard() != null && sa.hasParam("ShowCardInPrompt")) {
            // The card wants another thing displayed in the prompt on mouse over rather than itself
            Card show = null;
            Object o = null;
            switch (sa.getParam("ShowCardInPrompt")) {
                case "FirstRemembered":
                    o = sa.getHostCard().getFirstRemembered();
                    if (o != null && o instanceof Card) {
                        show = (Card)o;
                    }
                    break;
                case "LastRemembered":
                    o = sa.getHostCard().getFirstRemembered();
                    if (o != null && o instanceof Card) {
                        show = (Card)o;
                    }
                    break;
            }
            if (sa != null) {
                tempShowCard(show);
                boolean result = InputConfirm.confirm(this, ((Card) sa.getHostCard().getFirstRemembered()).getView(), message);
                endTempShowCards();
                return result;
            }
        }

        // The general case: display the source of the SA in the prompt on mouse over
        return InputConfirm.confirm(this, sa, message);
    }

    @Override
    public boolean confirmBidAction(final SpellAbility sa, final PlayerActionConfirmMode bidlife, final String string,
            final int bid, final Player winner) {
        return InputConfirm.confirm(this, sa, string + " Highest Bidder " + winner);
    }

    @Override
    public boolean confirmStaticApplication(final Card hostCard, final GameEntity affected, final String logic,
            final String message) {
        return InputConfirm.confirm(this, CardView.get(hostCard), message);
    }

    @Override
    public boolean confirmTrigger(final WrappedAbility wrapper, final Map<String, String> triggerParams,
            final boolean isMandatory) {
        final SpellAbility sa = wrapper.getWrappedAbility();
        final Trigger regtrig = wrapper.getTrigger();
        if (getGui().shouldAlwaysAcceptTrigger(regtrig.getId())) {
            return true;
        }
        if (getGui().shouldAlwaysDeclineTrigger(regtrig.getId())) {
            return false;
        }
        final String cardName = sa.getHostCard() == null ? null : sa.getHostCard().getName();
        if (getGui().shouldAutoYieldCard(cardName)) {
            return true;
        }
        if(!FModel.getPreferences().getPrefBoolean(FPref.UI_SKIP_AUTO_PAY)) {
        	if (sa != null && sa.hasParam("Graft") && !sa.getTriggeringObjects().isEmpty()) {
        		final Map<String, Object> objs = sa.getTriggeringObjects();
        		if (objs.containsKey("Card")) {
                    final Card card = (Card) objs.get("Card");
                    if(sa.getActivatingPlayer() != card.getController()) {
                    	return false;
                    }
        		}
        	}
        }

        // triggers with costs can always be declined by not paying the cost
        if (sa.hasParam("Cost") && !sa.getParam("Cost").equals("0")) {
            return true;
        }

        final StringBuilder buildQuestion = new StringBuilder("Use triggered ability of ");
        buildQuestion.append(regtrig.getHostCard().toString()).append("?");
        if (!FModel.getPreferences().getPrefBoolean(FPref.UI_COMPACT_PROMPT)
                && !FModel.getPreferences().getPrefBoolean(FPref.UI_DETAILED_SPELLDESC_IN_PROMPT)) {
            // append trigger description unless prompt is compact or detailed
            // descriptions are on
            buildQuestion.append("\n(");
            buildQuestion.append(TextUtil.fastReplace(triggerParams.get("TriggerDescription"),
                    "CARDNAME", regtrig.getHostCard().getName()));
            buildQuestion.append(")");
        }
        final Map<String, Object> tos = sa.getTriggeringObjects();
        if (tos.containsKey("Attacker")) {
            buildQuestion.append("\nAttacker: " + tos.get("Attacker"));
        }
        if (tos.containsKey("Card")) {
            final Card card = (Card) tos.get("Card");
            if (card != null && (card.getController() == player || game.getZoneOf(card) == null
                    || game.getZoneOf(card).getZoneType().isKnown())) {
                buildQuestion.append("\nTriggered by: " + tos.get("Card"));
            }
        }

        final InputConfirm inp = new InputConfirm(this, buildQuestion.toString(), wrapper);
        inp.showAndWait();
        return inp.getResult();
    }

    @Override
    public Player chooseStartingPlayer(final boolean isFirstGame) {
        if (game.getPlayers().size() == 2) {
            final String prompt = String.format("%s, you %s\n\nWould you like to play or draw?", player.getName(),
                    isFirstGame ? " have won the coin toss." : " lost the last game.");
            final InputConfirm inp = new InputConfirm(this, prompt, "Play", "Draw");
            inp.showAndWait();
            return inp.getResult() ? this.player : this.player.getOpponents().get(0);
        } else {
            final String prompt = String.format(
                    "%s, you %s\n\nWho would you like to start this game? (Click on the portrait.)", player.getName(),
                    isFirstGame ? " have won the coin toss." : " lost the last game.");
            final InputSelectEntitiesFromList<Player> input = new InputSelectEntitiesFromList<Player>(this, 1, 1,
                    new FCollection<Player>(game.getPlayersInTurnOrder()));
            input.setMessage(prompt);
            input.showAndWait();
            return input.getFirstSelected();
        }
    }

    @Override
    public CardCollection orderBlockers(final Card attacker, final CardCollection blockers) {
        final CardView vAttacker = CardView.get(attacker);
        getGui().setPanelSelection(vAttacker);
        return game.getCardList(getGui().order("Choose Damage Order for " + vAttacker, "Damaged First",
                CardView.getCollection(blockers), vAttacker));
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        HashMap<CardView, Card> mapCVtoC = new HashMap<>();
        for (Card card : attackers) {
            mapCVtoC.put(card.getView(), card);
        }
        List<CardView> chosen;
        List<CardView> choices = new ArrayList<CardView>(mapCVtoC.keySet());
        chosen = getGui().order("Exert Attackers?", "Exerted", 0, choices.size(), choices, null, null, false);
        List<Card> chosenCards = new ArrayList<Card>();
        for (CardView cardView : chosen) {
            chosenCards.add(mapCVtoC.get(cardView));
        }
        return chosenCards;
    }

    @Override
    public CardCollection orderBlocker(final Card attacker, final Card blocker, final CardCollection oldBlockers) {
        final CardView vAttacker = CardView.get(attacker);
        getGui().setPanelSelection(vAttacker);
        return game.getCardList(getGui().insertInList(
                "Choose blocker after which to place " + vAttacker + " in damage order; cancel to place it first",
                CardView.get(blocker), CardView.getCollection(oldBlockers)));
    }

    @Override
    public CardCollection orderAttackers(final Card blocker, final CardCollection attackers) {
        final CardView vBlocker = CardView.get(blocker);
        getGui().setPanelSelection(vBlocker);
        return game.getCardList(getGui().order("Choose Damage Order for " + vBlocker, "Damaged First",
                CardView.getCollection(attackers), vBlocker));
    }

    @Override
    public void reveal(final CardCollectionView cards, final ZoneType zone, final Player owner, final String message) {
        reveal(CardView.getCollection(cards), zone, PlayerView.get(owner), message);
    }

    @Override
    public void reveal(final List<CardView> cards, final ZoneType zone, final PlayerView owner, String message) {
        if (StringUtils.isBlank(message)) {
            message = "Looking at cards in {player's} " + zone.name().toLowerCase();
        } else {
            message += "{player's} " + zone.name().toLowerCase();
        }
        final String fm = MessageUtil.formatMessage(message, getLocalPlayerView(), owner);
        if (!cards.isEmpty()) {
            tempShowCards(game.getCardList(cards));
            getGui().reveal(fm, cards);
            endTempShowCards();
        } else {
            getGui().message(MessageUtil.formatMessage("There are no cards in {player's} " + zone.name().toLowerCase(),
                    player, owner), fm);
        }
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(final CardCollection topN) {
        CardCollection toBottom = null;
        CardCollection toTop = null;

        tempShowCards(topN);
        if (topN.size() == 1) {
            if (willPutCardOnTop(topN.get(0))) {
                toTop = topN;
            } else {
                toBottom = topN;
            }
        } else {
            toBottom = game.getCardList(getGui().many("Select cards to be put on the bottom of your library",
                    "Cards to put on the bottom", -1, CardView.getCollection(topN), null));
            topN.removeAll((Collection<?>) toBottom);
            if (topN.isEmpty()) {
                toTop = null;
            } else if (topN.size() == 1) {
                toTop = topN;
            } else {
                toTop = game.getCardList(getGui().order("Arrange cards to be put on top of your library",
                        "Top of Library", CardView.getCollection(topN), null));
            }
        }
        endTempShowCards();
        return ImmutablePair.of(toTop, toBottom);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(final CardCollection topN) {
        CardCollection toGrave = null;
        CardCollection toTop = null;

        tempShowCards(topN);
        if (topN.size() == 1) {
            final Card c = topN.getFirst();
            final CardView view = CardView.get(c);

            tempShowCard(c);
            getGui().setCard(view);
            boolean result = false;
            result = InputConfirm.confirm(this, view, TextUtil.concatNoSpace("Put ", view.toString(), " on the top of library or graveyard?"),
                    true, ImmutableList.of("Library", "Graveyard"));
            if (result) {
                toTop = topN;
            } else {
                toGrave = topN;
            }
        } else {
            toGrave = game.getCardList(getGui().many("Select cards to be put into the graveyard",
                    "Cards to put in the graveyard", -1, CardView.getCollection(topN), null));
            topN.removeAll((Collection<?>) toGrave);
            if (topN.isEmpty()) {
                toTop = null;
            } else if (topN.size() == 1) {
                toTop = topN;
            } else {
                toTop = game.getCardList(getGui().order("Arrange cards to be put on top of your library",
                        "Top of Library", CardView.getCollection(topN), null));
            }
        }
        endTempShowCards();
        return ImmutablePair.of(toTop, toGrave);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        final CardView view = CardView.get(c);

        tempShowCard(c);
        getGui().setCard(c.getView());

        boolean result = false;
        result = InputConfirm.confirm(this, view, TextUtil.concatNoSpace("Put ", view.toString(), " on the top or bottom of your library?"),
                true, ImmutableList.of("Top", "Bottom"));

        endTempShowCards();
        return result;
    }

    @Override
    public CardCollectionView orderMoveToZoneList(final CardCollectionView cards, final ZoneType destinationZone, final SpellAbility source) {
        if (source == null || source.getApi() != ApiType.ReorderZone) {
            if (destinationZone == ZoneType.Graveyard) {
                switch (FModel.getPreferences().getPref(FPref.UI_ALLOW_ORDER_GRAVEYARD_WHEN_NEEDED)) {
                    case ForgeConstants.GRAVEYARD_ORDERING_NEVER:
                        // No ordering is ever performed by the player except when done by effect (AF ReorderZone)
                        return cards;
                    case ForgeConstants.GRAVEYARD_ORDERING_OWN_CARDS:
                        // Order only if the relevant cards controlled by the player determine the potential necessity for it
                        if (!game.isGraveyardOrdered(player)) {
                            return cards;
                        }
                        break;
                    case ForgeConstants.GRAVEYARD_ORDERING_ALWAYS:
                        // Always order cards, no matter if there is a determined case for it or not
                        break;
                    default:
                        // By default, assume no special ordering necessary (but should not get here unless the preference file is borked)
                        return cards;
                }
            }
        }

        List<CardView> choices;
        tempShowCards(cards);
        switch (destinationZone) {
        case Library:
            choices = getGui().order("Choose order of cards to put into the library", "Closest to top",
                    CardView.getCollection(cards), null);
            break;
        case Battlefield:
            choices = getGui().order("Choose order of cards to put onto the battlefield", "Put first",
                    CardView.getCollection(cards), null);
            break;
        case Graveyard:
            choices = getGui().order("Choose order of cards to put into the graveyard", "Closest to bottom",
                    CardView.getCollection(cards), null);
            break;
        case PlanarDeck:
            choices = getGui().order("Choose order of cards to put into the planar deck", "Closest to top",
                    CardView.getCollection(cards), null);
            break;
        case SchemeDeck:
            choices = getGui().order("Choose order of cards to put into the scheme deck", "Closest to top",
                    CardView.getCollection(cards), null);
            break;
        case Stack:
            choices = getGui().order("Choose order of copies to cast", "Put first", CardView.getCollection(cards),
                    null);
            break;
        default:
            System.out.println("ZoneType " + destinationZone + " - Not Ordered");
            endTempShowCards();
            return cards;
        }
        endTempShowCards();
        return game.getCardList(choices);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(final Player p, final SpellAbility sa,
            final CardCollection valid, final int min, final int max) {
        if (p != player) {
            tempShowCards(valid);
            final CardCollection choices = game
                    .getCardList(getGui().many("Choose " + min + " card" + (min != 1 ? "s" : "") + " to discard",
                            "Discarded", min, min, CardView.getCollection(valid), null));
            endTempShowCards();
            return choices;
        }

        final InputSelectCardsFromList inp = new InputSelectCardsFromList(this, min, max, valid, sa);
        inp.setMessage(sa.hasParam("AnyNumber") ? "Discard up to %d card(s)" : "Discard %d card(s)");
        inp.showAndWait();
        return new CardCollection(inp.getSelected());
    }

    @Override
    public CardCollectionView chooseCardsToDelve(final int genericAmount, final CardCollection grave) {
        final int cardsInGrave = Math.min(genericAmount, grave.size());
        if (cardsInGrave == 0) {
            return CardCollection.EMPTY;
        }

        final CardCollection toExile = new CardCollection();
        final ImmutableList.Builder<Integer> cntChoice = ImmutableList.builder();
        for (int i = 0; i <= cardsInGrave; i++) {
            cntChoice.add(Integer.valueOf(i));
        }
        final int chosenAmount = getGui().one("Delve how many cards?", cntChoice.build()).intValue();
        for (int i = 0; i < chosenAmount; i++) {
            final CardView nowChosen = getGui().oneOrNone("Exile which card?", CardView.getCollection(grave));

            if (nowChosen == null) {
                // User canceled,abort delving.
                toExile.clear();
                break;
            }

            final Card card = game.getCard(nowChosen);
            grave.remove(card);
            toExile.add(card);
        }
        return toExile;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.game.player.PlayerController#chooseTargets(forge.card.spellability.
     * SpellAbility, forge.card.spellability.SpellAbilityStackInstance)
     */
    @Override
    public TargetChoices chooseNewTargetsFor(final SpellAbility ability) {
        final SpellAbility sa = ability.isWrapper() ? ((WrappedAbility) ability).getWrappedAbility() : ability;
        if (sa.getTargetRestrictions() == null) {
            return null;
        }
        final TargetChoices oldTarget = sa.getTargets();
        final TargetSelection select = new TargetSelection(this, sa);
        sa.resetTargets();
        final TargetRestrictions tg = sa.getTargetRestrictions();
        if (tg != null) {
            tg.calculateStillToDivide(sa.getParam("DividedAsYouChoose"), sa.getHostCard(), sa);
        }
        if (select.chooseTargets(oldTarget.getNumTargeted(), true)) {
            return sa.getTargets();
        } else {
            // Return old target, since we had to reset them above
            return oldTarget;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.game.player.PlayerController#chooseCardsToDiscardUnlessType(int,
     * java.lang.String, forge.card.spellability.SpellAbility)
     */
    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(final int num, final CardCollectionView hand,
            final String uType, final SpellAbility sa) {
        final InputSelectEntitiesFromList<Card> target = new InputSelectEntitiesFromList<Card>(this, num, num, hand,
                sa) {
            private static final long serialVersionUID = -5774108410928795591L;

            @Override
            protected boolean hasAllTargets() {
                for (final Card c : selected) {
                    if (c.getType().hasStringType(uType)) {
                        return true;
                    }
                }
                return super.hasAllTargets();
            }
        };
        target.setMessage("Select %d card(s) to discard, unless you discard a " + uType + ".");
        target.showAndWait();
        return new CardCollection(target.getSelected());
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.game.player.PlayerController#chooseManaFromPool(java.util.List)
     */
    @Override
    public Mana chooseManaFromPool(final List<Mana> manaChoices) {
        final List<String> options = Lists.newArrayList();
        for (int i = 0; i < manaChoices.size(); i++) {
            final Mana m = manaChoices.get(i);
            options.add(TextUtil.concatNoSpace(String.valueOf(1 + i), ". ", MagicColor.toLongString(m.getColor()),
                    " mana from ", m.getSourceCard().toString()));
        }
        final String chosen = getGui().one("Pay Mana from Mana Pool", options);
        final String idx = TextUtil.split(chosen, '.')[0];
        return manaChoices.get(Integer.parseInt(idx) - 1);
    }

    /*
     * (non-Javadoc)
     * 
     * @see forge.game.player.PlayerController#chooseSomeType(java.lang.String,
     * java.lang.String, java.util.List, java.util.List, java.lang.String)
     */
    @Override
    public String chooseSomeType(final String kindOfType, final SpellAbility sa, final List<String> validTypes,
            final List<String> invalidTypes, final boolean isOptional) {
        final List<String> types = Lists.newArrayList(validTypes);
        if (invalidTypes != null && !invalidTypes.isEmpty()) {
            Iterables.removeAll(types, invalidTypes);
        }
        if (kindOfType.equals("Creature")) {
            sortCreatureTypes(types);
        }
        if (isOptional) {
            return getGui().oneOrNone("Choose a " + kindOfType.toLowerCase() + " type", types);
        }
        return getGui().one("Choose a " + kindOfType.toLowerCase() + " type", types);
    }

    // sort creature types such that those most prevalent in player's deck are
    // sorted to the top
    private void sortCreatureTypes(List<String> types) {
        // build map of creature types in player's main deck against the
        // occurrences of each
        CardCollection pool = CardLists.filterControlledBy(game.getCardsInGame(), player);
        Map<String, Integer> typesInDeck = Maps.newHashMap();

        // TODO JAVA 8 use getOrDefault
        for (Card c : pool) {

            // Changeling are all creature types, they are not interesting for
            // counting creature types
            if (c.hasStartOfKeyword(Keyword.CHANGELING.toString())) {
                continue;
            }
            // ignore cards that does enter the battlefield as clones
            boolean isClone = false;
            for (ReplacementEffect re : c.getReplacementEffects()) {
                if (re.getLayer() == ReplacementLayer.Copy) {
                    isClone = true;
                    break;
                }
            }
            if (isClone) {
                continue;
            }

            Set<String> cardCreatureTypes = c.getType().getCreatureTypes();
            for (String type : cardCreatureTypes) {
                Integer count = typesInDeck.get(type);
                if (count == null) {
                    count = 0;
                }
                typesInDeck.put(type, count + 1);
            }
            // also take into account abilities that generate tokens
            for (SpellAbility sa : c.getAllSpellAbilities()) {
                if (sa.getApi() != ApiType.Token) {
                    continue;
                }
                if (sa.hasParam("TokenTypes")) {
                    for (String var : sa.getParam("TokenTypes").split(",")) {
                        if (!CardType.isACreatureType(var)) {
                            continue;
                        }
                        Integer count = typesInDeck.get(var);
                        if (count == null) {
                            count = 0;
                        }
                        typesInDeck.put(var, count + 1);
                    }
                }
            }
            // same for Trigger that does make Tokens
            for (Trigger t : c.getTriggers()) {
                SpellAbility sa = t.getOverridingAbility();
                String sTokenTypes = null;
                if (sa != null) {
                    if (sa.getApi() != ApiType.Token || !sa.hasParam("TokenTypes")) {
                        continue;
                    }
                    sTokenTypes = sa.getParam("TokenTypes");
                } else if (t.hasParam("Execute")) {
                    String name = t.getParam("Execute");
                    if (!c.hasSVar(name)) {
                        continue;
                    }

                    Map<String, String> params = AbilityFactory.getMapParams(c.getSVar(name));
                    if (!params.containsKey("TokenTypes")) {
                        continue;
                    }
                    sTokenTypes = params.get("TokenTypes");
                }

                if (sTokenTypes == null) {
                    continue;
                }

                for (String var : sTokenTypes.split(",")) {
                    if (!CardType.isACreatureType(var)) {
                        continue;
                    }
                    Integer count = typesInDeck.get(var);
                    if (count == null) {
                        count = 0;
                    }
                    typesInDeck.put(var, count + 1);
                }
            }
            // special rule for Fabricate and Servo
            if (c.hasStartOfKeyword(Keyword.FABRICATE.toString())) {
                Integer count = typesInDeck.get("Servo");
                if (count == null) {
                    count = 0;
                }
                typesInDeck.put("Servo", count + 1);
            }
        }

        // create sorted list from map from least to most frequent
        List<Entry<String, Integer>> sortedList = Lists.newArrayList(typesInDeck.entrySet());
        Collections.sort(sortedList, new Comparator<Entry<String, Integer>>() {
            public int compare(Entry<String, Integer> o1, Entry<String, Integer> o2) {
                return o1.getValue().compareTo(o2.getValue());
            }
        });

        // loop through sorted list and move each type to the front of the
        // validTypes collection
        for (Entry<String, Integer> entry : sortedList) {
            String type = entry.getKey();
            if (types.remove(type)) { // ensure an invalid type isn't introduced
                types.add(0, type);
            }
        }
    }

    @Override
    public Object vote(final SpellAbility sa, final String prompt, final List<Object> options,
            final ListMultimap<Object, Player> votes) {
        return getGui().one(prompt, options);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.game.player.PlayerController#confirmReplacementEffect(forge.card.
     * replacement.ReplacementEffect, forge.card.spellability.SpellAbility,
     * java.lang.String)
     */
    @Override
    public boolean confirmReplacementEffect(final ReplacementEffect replacementEffect, final SpellAbility effectSA,
            final String question) {
        final InputConfirm inp = new InputConfirm(this, question, effectSA);
        inp.showAndWait();
        return inp.getResult();
    }

    @Override
    public CardCollectionView getCardsToMulligan(final Player firstPlayer) {
        // Partial Paris is gone, so it being commander doesn't really matter
        // anymore...
        final InputConfirmMulligan inp = new InputConfirmMulligan(this, player, firstPlayer);
        inp.showAndWait();
        return inp.isKeepHand() ? null : player.getCardsIn(ZoneType.Hand);
    }

    @Override
    public void declareAttackers(final Player attackingPlayer, final Combat combat) {
        if (mayAutoPass()) {
            if (CombatUtil.validateAttackers(combat)) {
                return; // don't prompt to declare attackers if user chose to
                        // end the turn and not attacking is legal
            } else {
                autoPassCancel(); // otherwise: cancel auto pass because of this
                                  // unexpected attack
            }
        }

        // This input should not modify combat object itself, but should return
        // user choice
        final InputAttack inpAttack = new InputAttack(this, attackingPlayer, combat);
        inpAttack.showAndWait();
    }

    @Override
    public void declareBlockers(final Player defender, final Combat combat) {
        // This input should not modify combat object itself, but should return
        // user choice
        final InputBlock inpBlock = new InputBlock(this, defender, combat);
        inpBlock.showAndWait();
        getGui().updateAutoPassPrompt();
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final MagicStack stack = game.getStack();

        if (mayAutoPass()) {
            // avoid prompting for input if current phase is set to be
            // auto-passed
            // instead posing a short delay if needed to prevent the game
            // jumping ahead too quick
            int delay = 0;
            if (stack.isEmpty()) {
                // make sure to briefly pause at phases you're not set up to
                // skip
                if (!getGui().isUiSetToSkipPhase(game.getPhaseHandler().getPlayerTurn().getView(),
                        game.getPhaseHandler().getPhase())) {
                    delay = FControlGamePlayback.phasesDelay;
                }
            } else {
                // pause slightly longer for spells and abilities on the stack
                // resolving
                delay = FControlGamePlayback.resolveDelay;
            }
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (final InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        if (stack.isEmpty()) {
            if (getGui().isUiSetToSkipPhase(game.getPhaseHandler().getPlayerTurn().getView(),
                    game.getPhaseHandler().getPhase())) {
                return null; // avoid prompt for input if stack is empty and
                             // player is set to skip the current phase
            }
        } else {
            final SpellAbility ability = stack.peekAbility();
            final String cardName = ability.getHostCard() == null ? null : ability.getHostCard().getName();
            boolean shouldAutoYield = getGui().shouldAutoYieldCard(cardName)  || getGui().shouldAutoYield(ability.yieldKey());
            if(!shouldAutoYield && !FModel.getPreferences().getPrefBoolean(FPref.UI_SKIP_AUTO_PAY)) {
            	if (ability != null && ability.hasParam("Graft") && !ability.getTriggeringObjects().isEmpty()) {
            		final Map<String, Object> objs = ability.getTriggeringObjects();
            		if (objs.containsKey("Card")) {
                        final Card card = (Card) objs.get("Card");
                        if(ability.getActivatingPlayer() != card.getController()) {
                        	shouldAutoYield = true;
                        }
            		}
            	}
            }
            if (ability != null && ability.isAbility() && shouldAutoYield) {
                // avoid prompt for input if top ability of stack is set to
                // auto-yield
                try {
                    Thread.sleep(FControlGamePlayback.resolveDelay);
                } catch (final InterruptedException e) {
                    e.printStackTrace();
                }
                return null;
            }
        }

        final InputPassPriority defaultInput = new InputPassPriority(this);
        defaultInput.showAndWait();
        return defaultInput.getChosenSa();
    }

    @Override
    public boolean playChosenSpellAbility(final SpellAbility chosenSa) {
        HumanPlay.playSpellAbility(this, player, chosenSa, true);
        return true;
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(final int nDiscard) {
        final int max = player.getMaxHandSize();

        @SuppressWarnings("serial")
        final InputSelectCardsFromList inp = new InputSelectCardsFromList(this, nDiscard, nDiscard,
                player.getZone(ZoneType.Hand).getCards()) {
            @Override
            protected final boolean allowAwaitNextInput() {
                return true; // prevent Cleanup message getting stuck during
                             // opponent's next turn
            }
        };
        final String message = "Cleanup Phase\nSelect " + nDiscard + " card" + (nDiscard > 1 ? "s" : "")
                + " to discard to bring your hand down to the maximum of " + max + " cards.";
        inp.setMessage(message);
        inp.setCancelAllowed(false);
        inp.showAndWait();
        return new CardCollection(inp.getSelected());
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, final CardCollectionView valid) {
        max = Math.min(max, valid.size());
        min = Math.min(min, max);
        final InputSelectCardsFromList inp = new InputSelectCardsFromList(this, min, max, valid);
        inp.setMessage("Choose Which Cards to Reveal");
        inp.showAndWait();
        return new CardCollection(inp.getSelected());
    }

    @Override
    public boolean payManaOptional(final Card c, final Cost cost, final SpellAbility sa, final String prompt,
            final ManaPaymentPurpose purpose) {
        if (sa == null && cost.isOnlyManaCost() && cost.getTotalMana().isZero()
                && !FModel.getPreferences().getPrefBoolean(FPref.MATCHPREF_PROMPT_FREE_BLOCKS)) {
            return true;
        }
        return HumanPlay.payCostDuringAbilityResolve(this, player, c, cost, sa, prompt);
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(final List<SpellAbility> usableFromOpeningHand) {
        final CardCollection srcCards = new CardCollection();
        for (final SpellAbility sa : usableFromOpeningHand) {
            srcCards.add(sa.getHostCard());
        }
        final List<SpellAbility> result = Lists.newArrayList();
        if (srcCards.isEmpty()) {
            return result;
        }
        final List<CardView> chosen = getGui().many("Choose cards to activate from opening hand and their order",
                "Activate first", -1, CardView.getCollection(srcCards), null);
        for (final CardView view : chosen) {
            final Card c = game.getCard(view);
            for (final SpellAbility sa : usableFromOpeningHand) {
                if (sa.getHostCard() == c) {
                    result.add(sa);
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public boolean chooseBinary(final SpellAbility sa, final String question, final BinaryChoiceType kindOfChoice,
            final Boolean defaultVal) {
        final List<String> labels;
        switch (kindOfChoice) {
        case HeadsOrTails:
            labels = ImmutableList.of("Heads", "Tails");
            break;
        case TapOrUntap:
            labels = ImmutableList.of("Tap", "Untap");
            break;
        case OddsOrEvens:
            labels = ImmutableList.of("Odds", "Evens");
            break;
        case UntapOrLeaveTapped:
            labels = ImmutableList.of("Untap", "Leave tapped");
            break;
        case UntapTimeVault:
            labels = ImmutableList.of("Untap (and skip this turn)", "Leave tapped");
            break;
        case PlayOrDraw:
            labels = ImmutableList.of("Play", "Draw");
            break;
        case LeftOrRight:
            labels = ImmutableList.of("Left", "Right");
            break;
        case AddOrRemove:
            labels = ImmutableList.of("Add Counter", "Remove Counter");
            break;
        default:
            labels = ImmutableList.copyOf(kindOfChoice.toString().split("Or"));
        }

        return InputConfirm.confirm(this, sa, question, defaultVal == null || defaultVal.booleanValue(), labels);
    }

    @Override
    public boolean chooseFlipResult(final SpellAbility sa, final Player flipper, final boolean[] results,
            final boolean call) {
        final String[] labelsSrc = call ? new String[] { "heads", "tails" }
                : new String[] { "win the flip", "lose the flip" };
        final ImmutableList.Builder<String> strResults = ImmutableList.<String>builder();
        for (int i = 0; i < results.length; i++) {
            strResults.add(labelsSrc[results[i] ? 0 : 1]);
        }
        return getGui().one(sa.getHostCard().getName() + " - Choose a result", strResults.build()).equals(labelsSrc[0]);
    }

    @Override
    public Card chooseProtectionShield(final GameEntity entityBeingDamaged, final List<String> options,
            final Map<String, Card> choiceMap) {
        final String title = entityBeingDamaged + " - select which prevention shield to use";
        return choiceMap.get(getGui().one(title, options));
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(final SpellAbility saSpellskite,
            final List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        if (allTargets.size() < 2) {
            return Iterables.getFirst(allTargets, null);
        }

        final List<Pair<SpellAbilityStackInstance, GameObject>> chosen = getGui()
                .getChoices(saSpellskite.getHostCard().getName(), 1, 1, allTargets, null, new FnTargetToString());
        return Iterables.getFirst(chosen, null);
    }

    private final static class FnTargetToString
            implements Function<Pair<SpellAbilityStackInstance, GameObject>, String>, Serializable {
        private static final long serialVersionUID = -4779137632302777802L;

        @Override
        public String apply(final Pair<SpellAbilityStackInstance, GameObject> targ) {
            return targ.getRight().toString() + " - " + targ.getLeft().getStackDescription();
        }
    }

    @Override
    public void notifyOfValue(final SpellAbility sa, final GameObject realtedTarget, final String value) {
        final String message = MessageUtil.formatNotificationMessage(sa, player, realtedTarget, value);
        if (sa != null && sa.isManaAbility()) {
            game.getGameLog().add(GameLogEntryType.LAND, message);
        } else {
            getGui().message(message,
                    sa == null || sa.getHostCard() == null ? "" : CardView.get(sa.getHostCard()).toString());
        }
    }

    // end of not related candidates for move.

    /*
     * (non-Javadoc)
     * 
     * @see forge.game.player.PlayerController#chooseModeForAbility(forge.card.
     * spellability.SpellAbility, java.util.List, int, int)
     */
    @Override
    public List<AbilitySub> chooseModeForAbility(final SpellAbility sa, final int min, final int num,
            boolean allowRepeat) {
        final List<AbilitySub> choices = CharmEffect.makePossibleOptions(sa);
        final String modeTitle = TextUtil.concatNoSpace(sa.getActivatingPlayer().toString(), " activated ",
                sa.getHostCard().toString(), " - Choose a mode");
        final List<AbilitySub> chosen = Lists.newArrayListWithCapacity(num);

        final String cardName = sa.getHostCard() == null ? null : sa.getHostCard().getName();
        if(num == 1 && choices.size() == 2 && getGui().shouldAutoYieldCard(cardName)) {
        	if(choices.get(0).toString().startsWith("Untap ") && choices.get(1).toString().startsWith("Tap ")) {
            	chosen.add(choices.get(0));
            	return chosen;
        	}
        	if(choices.get(1).toString().startsWith("Untap ") && choices.get(0).toString().startsWith("Tap ")) {
            	chosen.add(choices.get(1));
            	return chosen;
        	}
        }

        for (int i = 0; i < num; i++) {
            AbilitySub a;
            if (i < min) {
                a = getGui().one(modeTitle, choices);
            } else {
                a = getGui().oneOrNone(modeTitle, choices);
            }
            if (a == null) {
                break;
            }

            if (!allowRepeat) {
                choices.remove(a);
            }
            chosen.add(a);
        }
        return chosen;
    }

    @Override
    public List<String> chooseColors(final String message, final SpellAbility sa, final int min, final int max,
            final List<String> options) {
        return getGui().getChoices(message, min, max, options);
    }

    @Override
    public byte chooseColor(final String message, final SpellAbility sa, final ColorSet colors) {
        final int cntColors = colors.countColors();
        switch (cntColors) {
        case 0:
            return 0;
        case 1:
            return colors.getColor();
        default:
            return chooseColorCommon(message, sa == null ? null : sa.getHostCard(), colors, false);
        }
    }

    @Override
    public byte chooseColorAllowColorless(final String message, final Card c, final ColorSet colors) {
        final int cntColors = 1 + colors.countColors();
        switch (cntColors) {
        case 1:
            return 0;
        default:
            return chooseColorCommon(message, c, colors, true);
        }
    }

    private byte chooseColorCommon(final String message, final Card c, final ColorSet colors,
            final boolean withColorless) {
        final ImmutableList.Builder<String> colorNamesBuilder = ImmutableList.builder();
        if (withColorless) {
            colorNamesBuilder.add(MagicColor.toLongString(MagicColor.COLORLESS));
        }
        for (final Byte b : colors) {
            colorNamesBuilder.add(MagicColor.toLongString(b.byteValue()));
        }
        final ImmutableList<String> colorNames = colorNamesBuilder.build();
        if (colorNames.size() > 2) {
            return MagicColor.fromName(getGui().one(message, colorNames));
        }

        boolean confirmed = false;
        confirmed = InputConfirm.confirm(this, CardView.get(c), message, true, colorNames);
        final int idxChosen = confirmed ? 0 : 1;
        return MagicColor.fromName(colorNames.get(idxChosen));
    }

    @Override
    public ICardFace chooseSingleCardFace(final SpellAbility sa, final String message, final Predicate<ICardFace> cpp,
            final String name) {
        final Iterable<ICardFace> cardsFromDb = FModel.getMagicDb().getCommonCards().getAllFaces();
        final List<ICardFace> cards = Lists.newArrayList(Iterables.filter(cardsFromDb, cpp));
        Collections.sort(cards);
        return getGui().one(message, cards);
    }

    @Override
    public CounterType chooseCounterType(final List<CounterType> options, final SpellAbility sa, final String prompt,
            Map<String, Object> params) {
        if (options.size() <= 1) {
            return Iterables.getFirst(options, null);
        }
        return getGui().one(prompt, options);
    }

    @Override
    public boolean confirmPayment(final CostPart costPart, final String question, SpellAbility sa) {
    	if(costPart.mustPay()) {
    		return true;
    	}
        final InputConfirm inp = new InputConfirm(this, question, sa);
        inp.showAndWait();
        return inp.getResult();
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(final String prompt,
            final List<ReplacementEffect> possibleReplacers, final Map<String, Object> runParams) {
        final ReplacementEffect first = possibleReplacers.get(0);
        if (possibleReplacers.size() == 1) {
            return first;
        }
        for(ReplacementEffect re : possibleReplacers) {
        	if(re.getOverridingAbility() != null && re.getOverridingAbility().hasParam("ChooseFirst")) {
        		return re;
        	}
        }
        String validStackSa = null;
        for(ReplacementEffect re : possibleReplacers) {
        	if(!re.hasParam("ValidStackSa")) {
        		validStackSa = null;
        		break;
        	}
        	if(validStackSa == null) {
        		validStackSa = re.getParam("ValidStackSa");
        	} else if(!re.getParam("ValidStackSa").equals(validStackSa)) {
        		validStackSa = null;
        		break;
        	}
        }
        if(validStackSa != null) {
        	return first;
        }
        final String firstStr = first.toString();
        for (int i = 1; i < possibleReplacers.size(); i++) {
            // prompt user if there are multiple different options
            if (!possibleReplacers.get(i).toString().equals(firstStr)) {
                return getGui().one(prompt, possibleReplacers);
            }
        }
        return first; // return first option without prompting if all options
                      // are the same
    }

    @Override
    public String chooseProtectionType(final String string, final SpellAbility sa, final List<String> choices) {
        return getGui().one(string, choices);
    }

    @Override
    public boolean payCostToPreventEffect(final Cost cost, final SpellAbility sa, final boolean alreadyPaid,
            final FCollectionView<Player> allPayers) {
        // if it's paid by the AI already the human can pay, but it won't change
        // anything
        return HumanPlay.payCostDuringAbilityResolve(this, player, sa.getHostCard(), cost, sa, null);
    }

    // stores saved order for different sets of SpellAbilities
    private final Map<String, List<Integer>> orderedSALookup = Maps.newHashMap();
    private final Map<String, Long> orderedSALookupTimestamp = Maps.newHashMap();

    @Override
    public void orderAndPlaySimultaneousSa(final List<SpellAbility> sas) {
    	List<SpellAbility> perCardList = Lists.newArrayList();
    	HashMap<Card, List<SpellAbility>> perCardmap = Maps.newHashMap();
    	List<SpellAbility> noHostList = Lists.newArrayList();
    	for(SpellAbility sa : sas) {
    		Card host = sa.getHostCard();
    		if(host != null) {
    			if(!perCardmap.containsKey(host)) {
    				perCardmap.put(host, Lists.newArrayList());
    			}
    			perCardmap.get(host).add(sa);
    		} else {
    			noHostList.add(sa);
    		}
    	}
    	for(Map.Entry<Card, List<SpellAbility>> entry : perCardmap.entrySet()) {
    		perCardList.addAll(entry.getValue());
    	}
    	perCardList.addAll(noHostList);

    	List<SpellAbility> activePlayerSAs = Lists.newArrayList();
    	for(SpellAbility sa : perCardList) {
    		if(sa.getApi() != null && (sa.getApi() == ApiType.Untap || sa.getApi() == ApiType.Token)) {
    			activePlayerSAs.add(sa);
    		}
    	}
    	perCardList.removeAll(activePlayerSAs);
    	activePlayerSAs.addAll(perCardList);

        List<SpellAbility> orderedSAs = activePlayerSAs;
        if (activePlayerSAs.size() > 1) {
            final String firstStr = activePlayerSAs.get(0).toString();
            boolean needPrompt = false;

            // for the purpose of pre-ordering, no need for extra granularity
            Integer idxAdditionalInfo = firstStr.indexOf(" [");
            String saLookupKey = idxAdditionalInfo != -1 ? firstStr.substring(0, idxAdditionalInfo - 1) : firstStr;

            char delim = (char) 5;
            for (int i = 1; i < activePlayerSAs.size(); i++) {
                SpellAbility currentSa = activePlayerSAs.get(i);
                String saStr = currentSa.toString();

                if (!needPrompt && !saStr.equals(firstStr)) {
                    needPrompt = true; // prompt by default unless all abilities
                                       // are the same
                }

                saLookupKey += delim + saStr;
                idxAdditionalInfo = saLookupKey.indexOf(" [");
                if (idxAdditionalInfo != -1) {
                    saLookupKey = saLookupKey.substring(0, idxAdditionalInfo - 1);
                }
            }
            if (needPrompt) {
                List<Integer> savedOrder = orderedSALookup.get(saLookupKey);
                List<SpellAbilityView> orderedSAVs = Lists.newArrayList();

                // create a mapping between a spell's view and the spell itself
                HashMap<SpellAbilityView, SpellAbility> spellViewCache = new HashMap<>();
                for (SpellAbility spellAbility : orderedSAs) {
                    spellViewCache.put(spellAbility.getView(), spellAbility);
                }

                if (savedOrder != null) {
                    orderedSAVs = Lists.newArrayList();
                    for (Integer index : savedOrder) {
                        orderedSAVs.add(activePlayerSAs.get(index).getView());
                    }
                } else {
                    for (SpellAbility spellAbility : orderedSAs) {
                        orderedSAVs.add(spellAbility.getView());
                    }
                }
                if (savedOrder != null) {
                    boolean preselect = FModel.getPreferences()
                            .getPrefBoolean(FPref.UI_PRESELECT_PREVIOUS_ABILITY_ORDER);
                    orderedSAVs = getGui().order("Reorder simultaneous abilities", "Resolve first", 0, 0,
                            preselect ? Lists.<SpellAbilityView>newArrayList() : orderedSAVs,
                            preselect ? orderedSAVs : Lists.<SpellAbilityView>newArrayList(), null, false);
                } else {
                    String refereceKey = null;
                    List<String> toSort = Lists.newArrayList(orderedSALookup.keySet());
                    Collections.sort(toSort, new Comparator<String>() {
                        @Override
                        public int compare(String s1, String s2) {
                            return (int) (orderedSALookupTimestamp.get(s2) - orderedSALookupTimestamp.get(s1));
                        }
                    });
                    for (String key : toSort) {
                        boolean found = true;
                        for (String s : key.split(delim + "")) {
                            if (!saLookupKey.contains(s)) {
                                found = false;
                                break;
                            }
                        }
                        if (found) {
                            refereceKey = key;
                            break;
                        }
                    }
                    if (refereceKey != null) {
                        List<SpellAbility> copyOrderedSAs = Lists.newArrayList(orderedSAs);
                        List<SpellAbility> newOrderedSAs = Lists.newArrayList();
                        savedOrder = orderedSALookup.get(refereceKey);
                        String[] strs = refereceKey.split(delim + "");
                        for (Integer index : savedOrder) {
                            if (index >= strs.length) {
                                continue;
                            }
                            for (SpellAbility sa : copyOrderedSAs) {
                                if (sa.toString().contains(strs[index])) {
                                    newOrderedSAs.add(sa);
                                    copyOrderedSAs.remove(sa);
                                    break;
                                }
                            }
                        }
                        for (SpellAbility sa : copyOrderedSAs) {
                            newOrderedSAs.add(sa);
                        }
                        orderedSAs = newOrderedSAs;
                    }
                    orderedSAVs = getGui().order("Select order for simultaneous abilities", "Resolve first", orderedSAVs,
                            null);
                }
                orderedSAs = Lists.newArrayList();
                for (SpellAbilityView spellAbilityView : orderedSAVs) {
                    orderedSAs.add(spellViewCache.get(spellAbilityView));
                }
                // save order to avoid needing to prompt a second time to order
                // the same abilities
                savedOrder = Lists.newArrayListWithCapacity(activePlayerSAs.size());
                for (SpellAbility sa : orderedSAs) {
                    savedOrder.add(activePlayerSAs.indexOf(sa));
                }
                orderedSALookup.put(saLookupKey, savedOrder);
                orderedSALookupTimestamp.put(saLookupKey, game.getTimestamp());
            }
        }
        for (int i = orderedSAs.size() - 1; i >= 0; i--) {
            final SpellAbility next = orderedSAs.get(i);
            if (next.isTrigger()) {
                HumanPlay.playSpellAbility(this, player, next, true);
            } else {
                player.getGame().getStack().add(next);
            }
        }
    }

    @Override
    public void playTrigger(final Card host, final WrappedAbility wrapperAbility, final boolean isMandatory) {
        HumanPlay.playSpellAbilityNoStack(this, player, wrapperAbility);
    }

    @Override
    public boolean playSaFromPlayEffect(final SpellAbility tgtSA) {
        return HumanPlay.playSpellAbility(this, player, tgtSA, true);
    }

    @Override
    public List<GameEntity> chooseProliferation(final SpellAbility sa, final int max) {
        final InputProliferate inp = new InputProliferate(this, sa, max);
        inp.setCancelAllowed(true);
        inp.showAndWait();
        if (inp.hasCancelled()) {
            return null;
        }
        return inp.getProliferation();
    }

    @Override
    public boolean chooseTargetsFor(final SpellAbility currentAbility) {
        final TargetSelection select = new TargetSelection(this, currentAbility);
        return select.chooseTargets(null);
    }

    @Override
    public boolean chooseCardsPile(final SpellAbility sa, final CardCollectionView pile1,
            final CardCollectionView pile2, final String faceUp) {
        final String p1Str = TextUtil.concatNoSpace("-- Pile 1 (", String.valueOf(pile1.size()), " cards) --");
        final String p2Str = TextUtil.concatNoSpace("-- Pile 2 (", String.valueOf(pile2.size()), " cards) --");

        /*
         * if (faceUp.equals("True")) { final List<String> possibleValues =
         * ImmutableList.of(p1Str , p2Str); return
         * getGui().confirm(CardView.get(sa.getHostCard()), "Choose a Pile",
         * possibleValues); }
         */

        final List<CardView> cards = Lists.newArrayListWithCapacity(pile1.size() + pile2.size() + 2);
        final CardView pileView1 = new CardView(Integer.MIN_VALUE, null, p1Str);

        cards.add(pileView1);
        if (faceUp.equals("False")) {
            tempShowCards(pile1);
            cards.addAll(CardView.getCollection(pile1));
        }

        final CardView pileView2 = new CardView(Integer.MIN_VALUE + 1, null, p2Str);
        cards.add(pileView2);
        if (!faceUp.equals("True")) {
            tempShowCards(pile2);
            cards.addAll(CardView.getCollection(pile2));
        }

        // make sure Pile 1 or Pile 2 is clicked on
        boolean result;
        while (true) {
            final CardView chosen = getGui().one("Choose a pile", cards);
            if (chosen.equals(pileView1)) {
                result = true;
                break;
            }
            if (chosen.equals(pileView2)) {
                result = false;
                break;
            }
        }

        endTempShowCards();
        return result;
    }

    @Override
    public void revealAnte(final String message, final Multimap<Player, PaperCard> removedAnteCards) {
        for (final Player p : removedAnteCards.keySet()) {
            getGui().reveal(message + " from " + Lang.getPossessedObject(MessageUtil.mayBeYou(player, p), "deck"),
                    ImmutableList.copyOf(removedAnteCards.get(p)));
        }
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(final List<PaperCard> losses) {
        return getGui().many("Select cards to add to your deck", "Add these to my deck", 0, losses.size(), losses,
                null);
    }

    @Override
    public boolean payManaCost(final ManaCost toPay, final CostPartMana costPartMana, final SpellAbility sa,
            final String prompt, final boolean isActivatedSa) {
        return HumanPlay.payManaCost(this, toPay, costPartMana, sa, player, prompt, isActivatedSa);
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(final SpellAbility sa, final ManaCost manaCost,
            final CardCollectionView untappedCards, boolean improvise) {
        final InputSelectCardsForConvokeOrImprovise inp = new InputSelectCardsForConvokeOrImprovise(this, player,
                manaCost, untappedCards, improvise, sa);
        inp.showAndWait();
        return inp.getConvokeMap();
    }

    @Override
    public String chooseCardName(final SpellAbility sa, final Predicate<ICardFace> cpp, final String valid,
            final String message) {
        while (true) {
            final ICardFace cardFace = chooseSingleCardFace(sa, message, cpp, sa.getHostCard().getName());
            final PaperCard cp = FModel.getMagicDb().getCommonCards().getCard(cardFace.getName());
            // the Card instance for test needs a game to be tested
            final Card instanceForPlayer = Card.fromPaperCard(cp, player);
            // TODO need the valid check be done against the CardFace?
            if (instanceForPlayer.isValid(valid, sa.getHostCard().getController(), sa.getHostCard(), sa)) {
                // it need to return name for card face
                return cardFace.getName();
            }
        }
    }

    @Override
    public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin,
            final SpellAbility sa, final CardCollection fetchList, final DelayedReveal delayedReveal,
            final String selectPrompt, final boolean isOptional, final Player decider) {
        return chooseSingleEntityForEffect(fetchList, delayedReveal, sa, selectPrompt, isOptional, decider);
    }
    
    @Override
    public CardCollection chooseCardsForZoneChange(ZoneType destination,
            List<ZoneType> origin, SpellAbility sa, CardCollection optionList, DelayedReveal delayedReveal,
            String selectPrompt, boolean isOptional, Player targetedPlayer, int changeNum) {
     // Human is supposed to read the message and understand from it what to
        // choose
        if (optionList.isEmpty()) {
            if (delayedReveal != null) {
                reveal(delayedReveal.getCards(), delayedReveal.getZone(), delayedReveal.getOwner(),
                        delayedReveal.getMessagePrefix());
            }
            return null;
        }
        if (!isOptional && optionList.size() == 1) {
            if (delayedReveal != null) {
                reveal(delayedReveal.getCards(), delayedReveal.getZone(), delayedReveal.getOwner(),
                        delayedReveal.getMessagePrefix());
            }
            return optionList;
        }

        boolean canUseSelectCardsInput = true;
        for (final GameEntity c : optionList) {
            if (c instanceof Player) {
                continue;
            }
            final Zone cz = ((Card) c).getZone();
            // can point at cards in own hand and anyone's battlefield
            final boolean canUiPointAtCards = cz != null
                    && (cz.is(ZoneType.Hand) && cz.getPlayer() == player || cz.is(ZoneType.Battlefield));
            if (!canUiPointAtCards) {
                canUseSelectCardsInput = false;
                break;
            }
        }

        if (canUseSelectCardsInput) {
            if (delayedReveal != null) {
                reveal(delayedReveal.getCards(), delayedReveal.getZone(), delayedReveal.getOwner(),
                        delayedReveal.getMessagePrefix());
            }
            CardCollection selected = new CardCollection();
            while(true) {
	            final InputSelectCardsFromList input = new InputSelectCardsFromList(this, isOptional ? 0 : 1, changeNum, optionList, sa);
	            input.setCancelAllowed(isOptional);
	            input.setMessage(MessageUtil.formatMessage(selectPrompt, player, targetedPlayer));
	            input.showAndWait();
	            selected = new CardCollection(input.getSelected());
	            if(!input.hasCancelled() && isOptional && !optionList.isEmpty() && input.getSelected().isEmpty()) {
	            	if(InputConfirm.confirm(this, sa, "Cancel?")) {
	            		break;
	            	}
	            } else {
	            	break;
	            }
            }
            return selected;
        }

        CardCollection collection = new CardCollection();
        int i = 0;
        do {
            tempShow(optionList);
            if (delayedReveal != null) {
                tempShow(delayedReveal.getCards());
            }
            final GameEntityView result = getGui().chooseSingleEntityForEffect("[" + (i + 1) + "] " + selectPrompt,
                    GameEntityView.getEntityCollection(optionList), i == 0 ? delayedReveal : null, isOptional, changeNum);
            endTempShowCards();

            if(changeNum == 0) {
                break;
            }

            boolean redo = false;
            if(result == null) {
                if (optionList.isEmpty()) {
                    break;
                } else if(confirmAction(sa, PlayerActionConfirmMode.ChangeZoneGeneral, "Cancel?")) {
                    break;
                } else {
                    redo = true;
                }
            } else {
                if (result instanceof CardView) {
                    collection.add(game.getCard((CardView)result));
                    optionList.remove(game.getCard((CardView)result));
                }
            }
            if(!redo)
                i++;
        } while (i < changeNum);

        return collection;
    }

    public List<Card> chooseCardsForZoneChange(final ZoneType destination, final List<ZoneType> origin,
            final SpellAbility sa, final CardCollection fetchList, final DelayedReveal delayedReveal,
            final String selectPrompt, final Player decider) {
        return chooseEntitiesForEffect(fetchList, delayedReveal, sa, selectPrompt, decider);
    }

    @Override
    public boolean isGuiPlayer() {
        return lobbyPlayer == GamePlayerUtil.getGuiPlayer();
    }

    public void updateAchievements() {
        AchievementCollection.updateAll(this);
    }

    public boolean canUndoLastAction() {
        if (!game.stack.canUndo(player)) {
            return false;
        }
        final Player priorityPlayer = game.getPhaseHandler().getPriorityPlayer();
        if (priorityPlayer == null || priorityPlayer != player) {
            return false;
        }
        return true;
    }

    @Override
    public void undoLastAction() {
        tryUndoLastAction();
    }

    public boolean tryUndoLastAction() {
        if (!canUndoLastAction()) {
            return false;
        }

        if (game.getStack().undo()) {
            final Input currentInput = inputQueue.getInput();
            if (currentInput instanceof InputPassPriority) {
                // ensure prompt updated if needed
                currentInput.showMessageInitial();
            }
            return true;
        }
        return false;
    }

    @Override
    public void selectButtonOk() {
        inputProxy.selectButtonOK();
    }

    @Override
    public void selectButtonCancel() {
        inputProxy.selectButtonCancel();
    }

    public void confirm() {
        if (inputQueue.getInput() instanceof InputConfirm) {
            selectButtonOk();
        }
    }

    @Override
    public void passPriority() {
        passPriority(false);
    }

    @Override
    public void passPriorityUntilEndOfTurn() {
        passPriority(true);
    }

    private void passPriority(final boolean passUntilEndOfTurn) {
        final Input inp = inputProxy.getInput();
        if (inp instanceof InputPassPriority) {
            if (passUntilEndOfTurn) {
                autoPassUntilEndOfTurn();
            }
            inp.selectButtonOK();
        } else {
            FThreads.invokeInEdtNowOrLater(new Runnable() {
                @Override
                public final void run() {
                    // getGui().message("Cannot pass priority at this time.");
                }
            });
        }
    }

    @Override
    public void useMana(final byte mana) {
        final Input input = inputQueue.getInput();
        if (input instanceof InputPayMana) {
            ((InputPayMana) input).useManaFromPool(mana);
        }
    }

    @Override
    public void selectPlayer(final PlayerView playerView, final ITriggerEvent triggerEvent) {
        inputProxy.selectPlayer(playerView, triggerEvent);
    }

    @Override
    public boolean selectCard(final CardView cardView, final List<CardView> otherCardViewsToSelect,
            final ITriggerEvent triggerEvent) {
        return inputProxy.selectCard(cardView, otherCardViewsToSelect, triggerEvent);
    }

    @Override
    public void selectAbility(final SpellAbilityView sa) {
        inputProxy.selectAbility(getGame().getSpellAbility(sa));
    }

    @Override
    public void alphaStrike() {
        inputProxy.alphaStrike();
    }

    @Override
    public void resetAtEndOfTurn() {
        // Not used by the human controller
    }

    // Dev Mode cheat functions
    private boolean canPlayUnlimited;

    @Override
    public boolean canPlayUnlimited() {
        return canPlayUnlimited;
    }

    private IDevModeCheats cheats;

    @Override
    public IDevModeCheats cheat() {
        if (cheats == null) {
            cheats = new DevModeCheats();
            // TODO: In Network game, inform other players that this player is
            // cheating
        }
        return cheats;
    }

    public boolean hasCheated() {
        return cheats != null;
    }

    public class DevModeCheats implements IDevModeCheats {
        private ICardFace lastAdded;
        private ZoneType lastAddedZone;
        private Player lastAddedPlayer;
        private SpellAbility lastAddedSA;
        private boolean lastTrigs;
        private boolean lastSummoningSickness;

        private DevModeCheats() {
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#setCanPlayUnlimited(boolean)
         */
        @Override
        public void setCanPlayUnlimited(final boolean canPlayUnlimited0) {
            canPlayUnlimited = canPlayUnlimited0;
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#setViewAllCards(boolean)
         */
        @Override
        public void setViewAllCards(final boolean canViewAll) {
            mayLookAtAllCards = canViewAll;
            for (final Player p : game.getPlayers()) {
                getGui().updateCards(CardView.getCollection(p.getAllCards()));
            }
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#generateMana()
         */
        @Override
        public void generateMana(boolean empty) {
            final Player pPriority = game.getPhaseHandler().getPriorityPlayer();
            if (pPriority == null) {
                getGui().message("No player has priority at the moment, so mana cannot be added to their pool.");
                return;
            }

            if (empty) {
                pPriority.getManaPool().clearPool(false);
            } else {
            final Card dummy = new Card(-777777, game);
                dummy.setOwner(pPriority);
                final Map<String, String> produced = Maps.newHashMap();
                produced.put("Produced", "W W W W W W W U U U U U U U B B B B B B B G G G G G G G R R R R R R R 7");
                final AbilityManaPart abMana = new AbilityManaPart(dummy, produced);
                game.getAction().invoke(new Runnable() {
                    @Override
                    public void run() {
                        abMana.produceMana(null);
                    }
                });
            }
        }

        private GameState createGameStateObject() {
            return new GameState() {
                @Override
                public IPaperCard getPaperCard(final String cardName) {
                    return FModel.getMagicDb().getCommonCards().getCard(cardName);
                }
            };
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#dumpGameState()
         */
        @Override
        public void dumpGameState(boolean quick) {
            final GameState state = createGameStateObject();
            try {
                state.initFromGame(game);
                File dir = new File(ForgeConstants.USER_GAMES_DIR);
                if(!dir.exists()) {
                    dir.mkdirs();
                }
                File f = null;
                if(quick) {
                	f = new File(dir + File.separator + "state_quick.txt");
                } else {
                	f = GuiBase.getInterface().getSaveFile(new File(ForgeConstants.USER_GAMES_DIR, "state.txt"));
                	if(f == null) {
                		return;
                	}
                }
                if (quick || (f != null
                        && (!f.exists() || getGui().showConfirmDialog("Overwrite existing file?", "File exists!")))) {
                	lastStateFileName = f.getAbsolutePath();
                    final BufferedWriter bw = new BufferedWriter(new FileWriter(f));
                    bw.write(state.toString());
                    bw.close();
                }
            } catch (final Exception e) {
                String err = e.getClass().getName();
                if (e.getMessage() != null) {
                    err += ": " + e.getMessage();
                }
                getGui().showErrorDialog(err);
                e.printStackTrace();
            }
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#setupGameState(boolean lastState)
         */
        @Override
        public void setupGameState(boolean lastState) {
            final File gamesDir = new File(ForgeConstants.USER_GAMES_DIR);
            if (!gamesDir.exists()) {
                // if the directory does not exist, try to create it
                gamesDir.mkdir();
            }

            String filename = null;
            if(lastState) {
                if(lastStateFileName == null) {
                	String name = gamesDir + File.separator + "state_quick.txt";
            		File file = new File(name);
            		if(file.exists()) {
            			filename = name;
            		}
                } else {
                	File file = new File(lastStateFileName);
                	if(file.exists()) {
                		filename = lastStateFileName;
                	}
                }
            }
        	if(filename == null) {
        		filename = GuiBase.getInterface().showFileDialog("Select Game State File",
                        ForgeConstants.USER_GAMES_DIR);
        	}
            if (filename == null) {
                return;
            }
            lastStateFileName = filename;

            final GameState state = createGameStateObject();
            try {
                final FileInputStream fstream = new FileInputStream(filename);
                state.parse(fstream);
                fstream.close();
            } catch (final FileNotFoundException fnfe) {
                SOptionPane.showErrorDialog("File not found: " + filename);
                return;
            } catch (final Exception e) {
                SOptionPane.showErrorDialog("Error loading battle setup file!");
                return;
            }

            final Player pPriority = game.getPhaseHandler().getPriorityPlayer();
            if (pPriority == null) {
                getGui().message("No player has priority at the moment, so game state cannot be setup.");
                return;
            }
            state.applyToGame(game);
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#tutorForCard()
         */
        @Override
        public void tutorForCard(boolean sideboard) {
            final Player pPriority = game.getPhaseHandler().getPriorityPlayer();
            if (pPriority == null) {
                getGui().message("No player has priority at the moment, so their deck can't be tutored from.");
                return;
            }

            final CardCollection lib = (CardCollection) pPriority.getCardsIn(sideboard ? ZoneType.Sideboard : ZoneType.Library);
            final List<ZoneType> origin = Lists.newArrayList();
            origin.add(sideboard ? ZoneType.Sideboard : ZoneType.Library);
            final SpellAbility sa = new SpellAbility.EmptySa(new Card(-1, game));
            final Card card = chooseSingleCardForZoneChange(ZoneType.Hand, origin, sa, lib, null, "Choose a card", true,
                    pPriority);
            if (card == null) {
                return;
            }

            game.getAction().invoke(new Runnable() {
                @Override
                public void run() {
                    game.getAction().moveToHand(card, null);
                }
            });
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#addCountersToPermanent()
         */
        @Override
        public void addCountersToPermanent(boolean player) {
            modifyCountersOnPermanent(false, player);
        }

        /*
         * (non-Javadoc)
         *
         * @see forge.player.IDevModeCheats#removeCountersToPermanent()
         */
        @Override
        public void removeCountersFromPermanent(boolean player) {
            modifyCountersOnPermanent(true, player);
        }

        public void modifyCountersOnPermanent(boolean subtract, boolean player) {
            if(player) {
                final Player p = game.getPlayer(getGui().oneOrNone("Add counters to which player?",
                        PlayerView.getCollection(game.getPlayers())));
                if(p == null) {
                	return;
                }
                final CounterType counter = getGui().oneOrNone("Which type of counter?",
                        subtract ? ImmutableList.copyOf(p.getCounters().keySet()) :
                            Lists.newArrayList(CounterType.ENERGY, CounterType.EXPERIENCE, CounterType.POISON));
                if (counter == null) {
                    return;
                }

                final Integer count = subtract ? getGui().getInteger("How many counters?", 1, p.getCounters(counter)) :
                        getGui().getInteger("How many counters?", 1, Integer.MAX_VALUE, 21);
                if (count == null) {
                    return;
                }

                if (subtract) {
                    p.subtractCounter(counter, count);
                } else {
                    p.addCounter(counter, count, null, false);
                }  
            } else {
                final String titleMsg = subtract ? "Remove counters from which card?" : "Add counters to which card?";
                final CardCollectionView cards = game.getCardsIn(ZoneType.listValueOf("Battlefield,Exile,Stack"));
                final Card card = game
                        .getCard(getGui().oneOrNone(titleMsg, CardView.getCollection(cards)));
                if (card == null) {
                    return;
                }
    
                final ImmutableList<CounterType> counters = subtract ? ImmutableList.copyOf(card.getCounters().keySet())
                    : CounterType.values;
    
                final CounterType counter = getGui().oneOrNone("Which type of counter?", counters);
                if (counter == null) {
                    return;
                }
    
                final Integer count = subtract ? getGui().getInteger("How many counters?", 1, card.getCounters(counter)) :
                    getGui().getInteger("How many counters?", 1, Integer.MAX_VALUE, 21);

                if (count == null) {
                    return;
                }
    
                if (subtract) {
                    card.subtractCounter(counter, count);
                } else {
                    card.addCounter(counter, count, card, false);
                }
            }

        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#tapPermanents()
         */
        @Override
        public void tapPermanents(final boolean all) {
            game.getAction().invoke(new Runnable() {
                @Override
                public void run() {
                    final CardCollectionView untapped = CardLists.filter(game.getCardsIn(ZoneType.Battlefield),
                            Predicates.not(CardPredicates.Presets.TAPPED));
                    if (all) {
                        final FCollection<Player> otherPlayers = new FCollection<Player>(game.getPlayers());
                        otherPlayers.remove(game.getPhaseHandler().getPriorityPlayer());
                        final CardCollectionView theirUntapped = CardLists.filter(untapped, CardPredicates.isControlledByAnyOf(otherPlayers));
                        for (final Card c : theirUntapped) {
                            c.tap();
                        }
                    } else {
                        final InputSelectCardsFromList inp = new InputSelectCardsFromList(PlayerControllerHuman.this, 0,
                                Integer.MAX_VALUE, untapped);
                        inp.setCancelAllowed(true);
                        inp.setMessage("Choose permanents to tap");
                        inp.showAndWait();
                        if (!inp.hasCancelled()) {
                            for (final Card c : inp.getSelected()) {
                                c.tap();
                            }
                        }
                    }
                }
            });
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#untapPermanents()
         */
        @Override
        public void untapPermanents(final boolean all) {
            game.getAction().invoke(new Runnable() {
                @Override
                public void run() {
                    final CardCollectionView tapped = CardLists.filter(game.getCardsIn(ZoneType.Battlefield),
                            CardPredicates.Presets.TAPPED);
                    if(all) {
                        final CardCollectionView myTapped = CardLists.filter(tapped, CardPredicates.isController(game.getPhaseHandler().getPriorityPlayer())); 
                        for (final Card c : myTapped) {
                            c.untap();
                        }
                    } else {
                        final InputSelectCardsFromList inp = new InputSelectCardsFromList(PlayerControllerHuman.this, 0,
                                Integer.MAX_VALUE, tapped);
                        inp.setCancelAllowed(true);
                        inp.setMessage("Choose permanents to untap");
                        inp.showAndWait();
                        if (!inp.hasCancelled()) {
                            for (final Card c : inp.getSelected()) {
                                c.untap();
                            }
                        }
                    }
                }
            });
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#setPlayerLife()
         */
        @Override
        public void setPlayerLife(boolean maxlife) {
            final Player player = game.getPlayer(
                    getGui().oneOrNone("Set life for which player?", PlayerView.getCollection(game.getPlayers())));
            if (player == null) {
                return;
            }

            Integer life = null;
            if(maxlife) {
                life = 99999;
            } else {
                life = getGui().getInteger("Set life to what?", 0);
                if (life == null) {
                    return;
                }
            }

            player.setLife(life, null);
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#winGame()
         */
        @Override
        public void winGame(boolean lose) {
            final Input input = inputQueue.getInput();
            if (!(input instanceof InputPassPriority)) {
                getGui().message("You must have priority to use this feature.", "Win Game");
                return;
            }

            // set life of all other players to 0
            final LobbyPlayer guiPlayer = getLobbyPlayer();
            final FCollectionView<Player> players = game.getPlayers();
            for (final Player player : players) {
                if (player.getLobbyPlayer() != guiPlayer ^ lose) {
                    player.setLife(0, null);
                }
            }

            // pass priority so that causes gui player to win
            input.selectButtonOK();
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#addCardToHand()
         */
        @Override
        public void addCardToHand(boolean mostCommon) {
            addCardToZone(ZoneType.Hand, false, false, mostCommon);
        }

        /*
         * (non-Javadoc)
         *
         * @see forge.player.IDevModeCheats#addCardToBattlefield()
         */
        @Override
        public void addCardToBattlefield(boolean mostCommon) {
            addCardToZone(ZoneType.Battlefield, false, true, mostCommon);
        }

        /*
         * (non-Javadoc)
         *
         * @see forge.player.IDevModeCheats#addCardToLibrary()
         */
        @Override
        public void addCardToLibrary(boolean mostCommon) {
            addCardToZone(ZoneType.Library, false, false, mostCommon);
        }

        /*
         * (non-Javadoc)
         *
         * @see forge.player.IDevModeCheats#addCardToGraveyard()
         */
        @Override
        public void addCardToGraveyard(boolean mostCommon) {
            addCardToZone(ZoneType.Graveyard, false, false, mostCommon);
        }

        /*
         * (non-Javadoc)
         *
         * @see forge.player.IDevModeCheats#addCardToExile()
         */
        @Override
        public void addCardToExile(boolean mostCommon) {
            addCardToZone(ZoneType.Exile, false, false, mostCommon);
        }

        /*
         * (non-Javadoc)
         *
        * @see forge.player.IDevModeCheats#addCardToExile()
        */
        @Override
        public void castASpell(boolean mostCommon) {
            addCardToZone(ZoneType.Battlefield, false, false, mostCommon);
        }

        /*
         * (non-Javadoc)
         *
         * @see forge.player.IDevModeCheats#repeatLastAddition()
         */
        @Override
        public void repeatLastAddition() {
            if (lastAdded == null || lastAddedZone == null) {
                return;
            }
            addCardToZone(null, true, lastTrigs, false);
        }

        private void addCardToZone(ZoneType zone, final boolean repeatLast, final boolean noTriggers, final boolean mostCommon) {
            final ZoneType targetZone = repeatLast ? lastAddedZone : zone;
            String zoneStr = targetZone != ZoneType.Battlefield ? "in " + targetZone.name().toLowerCase()
                    : noTriggers ? "on the battlefield" : "on the stack / in play";

            final Player p = repeatLast ? lastAddedPlayer
                    : game.getPlayer(getGui().oneOrNone("Put card " + zoneStr + " for which player?",
                    PlayerView.getCollection(game.getPlayers())));
            if (p == null) {
                return;
            }

            final CardDb carddb = FModel.getMagicDb().getCommonCards();
            List<ICardFace> faces = null;
            if(mostCommon) {
                faces = Lists.newArrayList(carddb.getFaceByName("Plains"), carddb.getFaceByName("Island"),
                		carddb.getFaceByName("Swamp"), carddb.getFaceByName("Mountain"), carddb.getFaceByName("Forest"),
                		carddb.getFaceByName("Murder"), carddb.getFaceByName("Cancel"), carddb.getFaceByName("One with Nothing"),
                		carddb.getFaceByName("Cloudshift"), carddb.getFaceByName("Sinkhole"), carddb.getFaceByName("Naturalize"),
                		carddb.getFaceByName("Lightning Bolt"), carddb.getFaceByName("Unsummon"), carddb.getFaceByName("Act of Treason"),
                		carddb.getFaceByName("Swords to Plowshares"), carddb.getFaceByName("Brainstorm"), carddb.getFaceByName("Grizzly Bears"),
                		carddb.getFaceByName("Resurrection"), carddb.getFaceByName("Evolving Wilds"));
            } else {
            	faces = Lists.newArrayList(carddb.getAllFaces());
            	Collections.sort(faces);
            }

            // use standard forge's list selection dialog
            final ICardFace f = repeatLast ? lastAdded : getGui().oneOrNone("Name the card", faces);
            if (f == null) {
                return;
            }

            final PaperCard c = carddb.getUniqueByName(f.getName());
            final Card forgeCard = Card.fromPaperCard(c, p);

            game.getAction().invoke(new Runnable() {
                @Override
                public void run() {
                    if (targetZone != ZoneType.Battlefield) {
                        game.getAction().moveTo(targetZone, forgeCard, null);
                    } else {
                        if (noTriggers) {
                            if (forgeCard.isPermanent() && !forgeCard.isAura()) {
                                if (forgeCard.isCreature()) {
                                    if (!repeatLast) {
                                        if (forgeCard.hasKeyword(Keyword.HASTE)) {
                                            lastSummoningSickness = true;
                                        } else {
                                            lastSummoningSickness = getGui().confirm(forgeCard.getView(),
                                                    TextUtil.concatWithSpace("Should", forgeCard.toString(), "be affected with Summoning Sickness?"));
                                        }
                                    }
                                }
                                game.getAction().moveTo(targetZone, forgeCard, null);
                                if (forgeCard.isCreature()) {
                                    forgeCard.setSickness(lastSummoningSickness);
                                }
                            } else {
                                getGui().message("The chosen card is not a permanent or can't exist independently on the battlefield.\nIf you'd like to cast a non-permanent spell, or if you'd like to cast a permanent spell and place it on stack, please use the Cast Spell/Play Land button.", "Error");
                                return;
                            }
                        } else {
                            if (c.getRules().getType().isLand()) {
                                // this is needed to ensure land abilities fire
                                game.getAction().moveToHand(forgeCard, null);
                                game.getAction().moveToPlay(forgeCard, null);
                                // ensure triggered abilities fire
                                game.getTriggerHandler().runWaitingTriggers();
                            } else {
                                final FCollectionView<SpellAbility> choices = forgeCard.getBasicSpells();
                                if (choices.isEmpty()) {
                                    return; // when would it happen?
                                }

                                final SpellAbility sa;
                                if (choices.size() == 1) {
                                    sa = choices.iterator().next();
                                } else {
                                    sa = repeatLast ? lastAddedSA : getGui().oneOrNone("Choose", (FCollection<SpellAbility>) choices);
                                }
                                if (sa == null) {
                                    return; // happens if cancelled
                                }

                                lastAddedSA = sa;

                                // this is really needed (for rollbacks at least)
                                game.getAction().moveToHand(forgeCard, null);
                                // Human player is choosing targets for an ability
                                // controlled by chosen player.
                                sa.setActivatingPlayer(p);
                                HumanPlay.playSaWithoutPayingManaCost(PlayerControllerHuman.this, game, sa, true);
                            }
                            // playSa could fire some triggers
                            game.getStack().addAllTriggeredAbilitiesToStack();
                        }
                    }

                    lastAdded = f;
                    lastAddedZone = targetZone;
                    lastAddedPlayer = p;
                    lastTrigs = noTriggers;
                }
            });
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#exileCardsFromHand()
         */
        @Override
        public void exileCardsFromHand() {
            final Player p = game.getPlayer(getGui().oneOrNone("Exile card(s) from which player's hand?",
                    PlayerView.getCollection(game.getPlayers())));
            if (p == null) {
                return;
            }

            final CardCollection selection;

            CardCollectionView cardsInHand = p.getCardsIn(ZoneType.Hand);
            selection = game.getCardList(getGui().many("Choose cards to exile", "Discarded", 0, -1,
                    CardView.getCollection(cardsInHand), null));

            if (selection != null && selection.size() > 0) {
                for (Card c : selection) {
                    if (c == null) {
                        continue;
                    }
                    if (game.getAction().moveTo(ZoneType.Exile, c, null) != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(p).append(" exiles ").append(c).append(" due to Dev Cheats.");
                        game.getGameLog().add(GameLogEntryType.DISCARD, sb.toString());
                    } else {
                        game.getGameLog().add(GameLogEntryType.INFORMATION, "DISCARD CHEAT ERROR");
                    }
                }
            }
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#exileCardsFromBattlefield()
         */
        @Override
        public void exileCardsFromBattlefield() {
            final Player p = game.getPlayer(getGui().oneOrNone("Exile card(s) from which player's battlefield?",
                    PlayerView.getCollection(game.getPlayers())));
            if (p == null) {
                return;
            }

            final CardCollection selection;

            CardCollectionView cardsInPlay = p.getCardsIn(ZoneType.Battlefield);
            selection = game.getCardList(getGui().many("Choose cards to exile", "Discarded", 0, -1,
                    CardView.getCollection(cardsInPlay), null));

            if (selection != null && selection.size() > 0) {
                for (Card c : selection) {
                    if (c == null) {
                        continue;
                    }
                    if (game.getAction().moveTo(ZoneType.Exile, c, null) != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(p).append(" exiles ").append(c).append(" due to Dev Cheats.");
                        game.getGameLog().add(GameLogEntryType.ZONE_CHANGE, sb.toString());
                    } else {
                        game.getGameLog().add(GameLogEntryType.INFORMATION, "EXILE FROM PLAY CHEAT ERROR");
                    }
                }
            }
        }

        /*
         * (non-Javadoc)
         *
         * @see forge.player.IDevModeCheats#removeCardsFromGame()
         */
        @Override
        public void removeCardsFromGame() {
            final Player p = game.getPlayer(getGui().oneOrNone("Remove card(s) belonging to which player?",
                    PlayerView.getCollection(game.getPlayers())));
            if (p == null) {
                return;
            }

            final String zone = getGui().one("Remove card(s) from which zone?",
                    Arrays.asList("Hand", "Battlefield", "Library", "Graveyard", "Exile"));

            final CardCollection selection;

            CardCollectionView cards = p.getCardsIn(ZoneType.smartValueOf(zone));
            selection = game.getCardList(getGui().many("Choose cards to remove from game", "Removed", 0, -1,
                    CardView.getCollection(cards), null));

            if (selection != null && selection.size() > 0) {
                for (Card c : selection) {
                    if (c == null) {
                        continue;
                    }
                    c.getZone().remove(c);
                    c.ceaseToExist();

                    StringBuilder sb = new StringBuilder();
                    sb.append(p).append(" removes ").append(c).append(" from game due to Dev Cheats.");
                    game.getGameLog().add(GameLogEntryType.ZONE_CHANGE, sb.toString());
                }
            }
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#riggedPlanarRoll()
         */
        @Override
        public void riggedPlanarRoll() {
            final Player player = game.getPlayer(
                    getGui().oneOrNone("Which player should roll?", PlayerView.getCollection(game.getPlayers())));
            if (player == null) {
                return;
            }

            final PlanarDice res = getGui().oneOrNone("Choose result", PlanarDice.values);
            if (res == null) {
                return;
            }

            System.out.println("Rigging planar dice roll: " + res.toString());

            game.getAction().invoke(new Runnable() {
                @Override
                public void run() {
                    PlanarDice.roll(player, res);
                }
            });
        }

        /*
         * (non-Javadoc)
         * 
         * @see forge.player.IDevModeCheats#planeswalkTo()
         */
        @Override
        public void planeswalkTo() {
            if (!game.getRules().hasAppliedVariant(GameType.Planechase)) {
                return;
            }
            final Player p = game.getPhaseHandler().getPlayerTurn();

            final List<PaperCard> allPlanars = Lists.newArrayList();
            for (final PaperCard c : FModel.getMagicDb().getVariantCards().getAllCards()) {
                if (c.getRules().getType().isPlane() || c.getRules().getType().isPhenomenon()) {
                    allPlanars.add(c);
                }
            }
            Collections.sort(allPlanars);

            // use standard forge's list selection dialog
            final IPaperCard c = getGui().oneOrNone("Name the card", allPlanars);
            if (c == null) {
                return;
            }
            final Card forgeCard = Card.fromPaperCard(c, p);

            forgeCard.setOwner(p);
            game.getAction().invoke(new Runnable() {
                @Override
                public void run() {
                    game.getAction().changeZone(null, p.getZone(ZoneType.PlanarDeck), forgeCard, 0, null);
                    PlanarDice.roll(p, PlanarDice.Planeswalk);
                }
            });
        }
    }

    private IMacroSystem macros;

    @Override
    public IMacroSystem macros() {
        if (macros == null) {
            macros = new MacroSystem();
        }
        return macros;
    }

    // Simple macro system implementation. Its goal is to simulate "clicking"
    // on cards/players in an automated way, to reduce mechanical overhead of
    // situations like repeated combo activation.
    public class MacroSystem implements IMacroSystem {
        // Position in the macro "sequence".
        private int sequenceIndex = 0;
        // "Actions" are stored as a pair of the "action" recipient (the entity
        // to "click") and a boolean representing whether the entity is a
        // player.
        private final List<Pair<GameEntityView, Boolean>> rememberedActions = Lists.newArrayList();
        private String rememberedSequenceText = "";

        @Override
        public void setRememberedActions() {
            final String dialogTitle = "Remember Action Sequence";
            // Not sure if this priority guard is really needed, but it seems
            // like an alright idea.
            final Input input = inputQueue.getInput();
            if (!(input instanceof InputPassPriority)) {
                getGui().message("You must have priority to use this feature.", dialogTitle);
                return;
            }

            int currentIndex = sequenceIndex;
            sequenceIndex = 0;
            // Use a Pair so we can keep a flag for isPlayer
            final List<Pair<Integer, Boolean>> entityInfo = Lists.newArrayList();
            final int playerID = getPlayer().getId();
            // Only support 1 opponent for now. There are some ideas about
            // supporting
            // multiplayer games in the future, but for now it would complicate
            // the parsing
            // process, and this implementation is still a "proof of concept".
            int opponentID = 0;
            for (final Player player : game.getPlayers()) {
                if (player.getId() != playerID) {
                    opponentID = player.getId();
                    break;
                }
            }

            // A more informative prompt would be useful, but the dialog seems
            // to
            // like to clip text in long messages...
            final String prompt = "Enter a sequence (card IDs and/or \"opponent\"/\"me\"). (e.g. 7, opponent, 18)";
            String textSequence = getGui().showInputDialog(prompt, dialogTitle, FSkinProp.ICO_QUEST_NOTES,
                    rememberedSequenceText);
            if (textSequence == null || textSequence.trim().isEmpty()) {
                rememberedActions.clear();
                if (!rememberedSequenceText.isEmpty()) {
                    rememberedSequenceText = "";
                    getGui().message("Action sequence cleared.", dialogTitle);
                }
                return;
            }
            // If they haven't changed the sequence, inform them the index is
            // reset
            // but don't change rememberedActions.
            if (textSequence.equals(rememberedSequenceText)) {
                if (currentIndex > 0 && currentIndex < rememberedActions.size()) {
                    getGui().message("Restarting action sequence.", dialogTitle);
                }
                return;
            }
            rememberedSequenceText = textSequence;
            rememberedActions.clear();

            // Clean up input
            textSequence = textSequence.trim().toLowerCase().replaceAll("[@%]", "");
            // Replace "opponent" and "me" with symbols to ease following
            // replacements
            textSequence = textSequence.replaceAll("\\bopponent\\b", "%").replaceAll("\\bme\\b", "@");
            // Strip user input of anything that's not a
            // digit/comma/whitespace/special symbol
            textSequence = textSequence.replaceAll("[^\\d\\s,@%]", "");
            // Now change various allowed delimiters to something neutral
            textSequence = textSequence.replaceAll("(,\\s+|,|\\s+)", "_");
            final String[] splitSequence = textSequence.split("_");
            for (final String textID : splitSequence) {
                if (StringUtils.isNumeric(textID)) {
                    entityInfo.add(Pair.of(Integer.valueOf(textID), false));
                } else if (textID.equals("%")) {
                    entityInfo.add(Pair.of(opponentID, true));
                } else if (textID.equals("@")) {
                    entityInfo.add(Pair.of(playerID, true));
                }
            }
            if (entityInfo.isEmpty()) {
                getGui().message("Error: Check IDs and ensure they're separated by spaces and/or commas.", dialogTitle);
                return;
            }

            // Fetch cards and players specified by the user input
            final ZoneType[] zones = { ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Command };
            final CardCollectionView cards = game.getCardsIn(Arrays.asList(zones));
            for (final Pair<Integer, Boolean> entity : entityInfo) {
                boolean found = false;
                // Nested loops are no fun; however, seems there's no better way
                // to get stuff by ID
                boolean isPlayer = entity.getValue();
                if (isPlayer) {
                    for (final Player player : game.getPlayers()) {
                        if (player.getId() == entity.getKey()) {
                            found = true;
                            rememberedActions.add(Pair.of((GameEntityView) player.getView(), true));
                            break;
                        }
                    }
                } else {
                    for (final Card card : cards) {
                        if (card.getId() == entity.getKey()) {
                            found = true;
                            rememberedActions.add(Pair.of((GameEntityView) card.getView(), false));
                            break;
                        }
                    }
                }
                if (!found) {
                    getGui().message("Error: Entity with ID " + entity.getKey() + " not found.", dialogTitle);
                    rememberedActions.clear();
                    return;
                }
            }
        }

        @Override
        public void nextRememberedAction() {
            final String dialogTitle = "Do Next Action in Sequence";
            if (rememberedActions.isEmpty()) {
                getGui().message("Please define an action sequence first.", dialogTitle);
                return;
            }
            if (sequenceIndex >= rememberedActions.size()) {
                // Wrap around to repeat the sequence
                sequenceIndex = 0;
            }
            final Pair<GameEntityView, Boolean> action = rememberedActions.get(sequenceIndex);
            final boolean isPlayer = action.getValue();
            if (isPlayer) {
                selectPlayer((PlayerView) action.getKey(), new DummyTriggerEvent());
            } else {
                selectCard((CardView) action.getKey(), null, new DummyTriggerEvent());
            }
            sequenceIndex++;
        }

        private class DummyTriggerEvent implements ITriggerEvent {
            @Override
            public int getButton() {
                return 1; // Emulate left mouse button
            }

            @Override
            public int getX() {
                return 0; // Hopefully this doesn't do anything wonky!
            }

            @Override
            public int getY() {
                return 0;
            }
        }
    }

    @Override
    public void concede() {
        if (player != null) {
            player.concede();
            getGame().getAction().checkGameOverCondition();
            isConcede = true;
        }
    }

    public boolean isConcede() {
    	return isConcede;
    }

    public boolean mayAutoPass() {
        return getGui().mayAutoPass(getLocalPlayerView());
    }

    public void autoPassUntilEndOfTurn() {
        getGui().autoPassUntilEndOfTurn(getLocalPlayerView());
    }

    @Override
    public void autoPassCancel() {
        getGui().autoPassCancel(getLocalPlayerView());
    }

    @Override
    public void awaitNextInput() {
        getGui().awaitNextInput();
    }

    @Override
    public void cancelAwaitNextInput() {
        getGui().cancelAwaitNextInput();
    }

    @Override
    public void nextGameDecision(final NextGameDecision decision) {
        game.fireEvent(new UiEventNextGameDecision(this, decision));
    }

    @Override
    public String getActivateDescription(final CardView card) {
        return getInputProxy().getActivateAction(card);
    }

    @Override
    public void reorderHand(final CardView card, final int index) {
        final PlayerZone hand = player.getZone(ZoneType.Hand);
        hand.reorder(game.getCard(card), index);
        player.updateZoneForView(hand);
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        ICardFace face = getGui().one(message, faces);
        return face == null ? "" : face.getName();
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return getGui().many("Choose cards to Splice onto", "Chosen Cards", 0, cards.size(), cards,
                sa.getHostCard().getView());
    }

    /*
     * (non-Javadoc)
     * 
     * @see forge.game.player.PlayerController#chooseOptionalCosts(forge.game.
     * spellability.SpellAbility, java.util.List)
     */
    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility choosen,
            List<OptionalCostValue> optionalCost) {
        return getGui().many("Choose optional Costs", "Optional Costs", 0, optionalCost.size(),
                optionalCost, choosen.getHostCard().getView());
    }

}
