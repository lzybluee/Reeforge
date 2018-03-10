package forge.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.game.cost.*;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.Spell;
import forge.util.TextUtil;
import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import forge.FThreads;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameLogEntryType;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.ability.effects.FlipCoinEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardDamageMap;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CardPredicates.Presets;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.match.input.InputPayMana;
import forge.match.input.InputPayManaOfCostPayment;
import forge.match.input.InputPayManaSimple;
import forge.match.input.InputSelectCardsFromList;
import forge.util.collect.FCollectionView;
import forge.util.Lang;
import forge.util.gui.SGuiChoose;


public class HumanPlay {

    private HumanPlay() {
    }

    /**
     * <p>
     * playSpellAbility.
     * </p>
     * 
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     */
    public final static boolean playSpellAbility(final PlayerControllerHuman controller, final Player p, SpellAbility sa) {
        FThreads.assertExecutedByEdt(false);

        if (sa == controller.getGame().PLAY_LAND_SURROGATE) {
            p.playLand(sa.getHostCard(), false);
            return false;
        }

        boolean castFaceDown = sa instanceof Spell && ((Spell)sa).isCastFaceDown();

        sa.setActivatingPlayer(p);
        Card source = sa.getHostCard();
        boolean flippedToCast = sa instanceof Spell && source.isFaceDown();

        source.setSplitStateToPlayAbility(sa);
        sa = chooseOptionalAdditionalCosts(p, sa);
        if (sa == null) {
            return false;
        }

        // extra play check
        if (sa.isSpell() && !sa.canPlay()) {
            // Exceptional cases where canPlay should not run
            boolean exemptFromCheck = false;
            if (source.hasSuspend() && p.getGame().isCardExiled(source) && source.getCounters(CounterType.TIME) == 0) {
                // A card is about to ETB from Suspend
                exemptFromCheck = true;
            }

            if (!exemptFromCheck) {
                return false;
            }
        }

        if (flippedToCast && !castFaceDown) {
            source.turnFaceUp(false, false);
        }

        if (sa.getApi() == ApiType.Charm && !sa.isWrapper()) {
            CharmEffect.makeChoices(sa);
        }

        if (sa.isSpell() && source.getType().hasStringType("Arcane")) {
            sa = AbilityUtils.addSpliceEffects(sa);
        }

        if (sa.hasParam("Bestow")) {
            source.animateBestow();
        }

        // Need to check PayCosts, and Ability + All SubAbilities for Target
        boolean newAbility = sa.getPayCosts() != null;
        SpellAbility ability = sa;
        while ((ability != null) && !newAbility) {
            final TargetRestrictions tgt = ability.getTargetRestrictions();

            newAbility |= tgt != null;
            ability = ability.getSubAbility();
        }

        // System.out.println("Playing:" + sa.getDescription() + " of " + sa.getHostCard() +  " new = " + newAbility);
        if (newAbility) {
            Cost abCost = sa.getPayCosts() == null ? new Cost("0", sa.isAbility()) : sa.getPayCosts();
            CostPayment payment = new CostPayment(abCost, sa);

            final HumanPlaySpellAbility req = new HumanPlaySpellAbility(controller, sa, payment);
            if (!req.playAbility(true, false, false)) {
                if (flippedToCast && !castFaceDown) {
                    source.turnFaceDown(true);
                }
                return false;
            }
        } else if (payManaCostIfNeeded(controller, p, sa)) {
            if (sa.isSpell() && !source.isCopiedSpell()) {
                sa.setHostCard(p.getGame().getAction().moveToStack(source, sa));
            }

            p.getGame().getStack().add(sa);
        } else {
            // Failed to pay costs, revert to original state
            if (flippedToCast && !castFaceDown) {
                source.turnFaceDown(true);
            }
            return false;
        }
        return true;
    }

    /**
     * choose optional additional costs. For HUMAN only
     * @param p
     * 
     * @param original
     *            the original sa
     * @return an ArrayList<SpellAbility>.
     */
    static SpellAbility chooseOptionalAdditionalCosts(Player p, final SpellAbility original) {
        if (!original.isSpell()) {
            return original;
        }

        PlayerController c = p.getController();

        // choose alternative additional cost
        final List<SpellAbility> abilities = GameActionUtil.getAdditionalCostSpell(original);

        final SpellAbility choosen = c.getAbilityToPlay(original.getHostCard(), abilities);

        List<OptionalCostValue> list =  GameActionUtil.getOptionalCostValues(choosen);
        if (!list.isEmpty()) {
            list = c.chooseOptionalCosts(choosen, list);
        }

        return GameActionUtil.addOptionalCosts(choosen, list);
        //final List<SpellAbility> abilities = GameActionUtil.getOptionalCosts(original);
    }

    private static boolean payManaCostIfNeeded(final PlayerControllerHuman controller, final Player p, final SpellAbility sa) {
        final ManaCostBeingPaid manaCost;
        if (sa.getHostCard().isCopiedSpell() && sa.isSpell()) {
            manaCost = new ManaCostBeingPaid(ManaCost.ZERO);
        }
        else {
            manaCost = new ManaCostBeingPaid(sa.getPayCosts().getTotalMana());
            CostAdjustment.adjust(manaCost, sa, null, false);
        }

        boolean isPaid = manaCost.isPaid();

        if (!isPaid) {
            InputPayManaSimple inputPay = new InputPayManaSimple(controller, p.getGame(), sa, manaCost);
            inputPay.showAndWait();
            isPaid = inputPay.isPaid();
        }
        return isPaid;
    }

    /**
     * <p>
     * playSpellAbilityForFree.
     * </p>
     * 
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     */
    public static final void playSaWithoutPayingManaCost(final PlayerControllerHuman controller, final Game game, SpellAbility sa, boolean mayChooseNewTargets) {
        FThreads.assertExecutedByEdt(false);
        final Card source = sa.getHostCard();

        source.setSplitStateToPlayAbility(sa);

        if (sa.getPayCosts() != null) {
            if (!sa.isCopied()) {
                if (sa.getApi() == ApiType.Charm && !sa.isWrapper()) {
                    CharmEffect.makeChoices(sa);
                }
                if (sa.isSpell() && source.getType().hasStringType("Arcane")) {
                    sa = AbilityUtils.addSpliceEffects(sa);
                }
            }
            final CostPayment payment = new CostPayment(sa.getPayCosts(), sa);

            final HumanPlaySpellAbility req = new HumanPlaySpellAbility(controller, sa, payment);
            req.playAbility(mayChooseNewTargets, true, false);
        }
        else {
            if (sa.isSpell()) {
                final Card c = sa.getHostCard();
                if (!c.isCopiedSpell()) {
                    sa.setHostCard(game.getAction().moveToStack(c, sa));
                }
            }
            game.getStack().add(sa);
        }
    }

    /**
     * <p>
     * playSpellAbility_NoStack.
     * </p>
     * 
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     */
    public final static void playSpellAbilityNoStack(final PlayerControllerHuman controller, final Player player, final SpellAbility sa) {
        playSpellAbilityNoStack(controller, player, sa, false);
    }

    public final static void playSpellAbilityNoStack(final PlayerControllerHuman controller, final Player player, final SpellAbility sa, boolean useOldTargets) {
        sa.setActivatingPlayer(player);

        if (sa.getPayCosts() != null) {
            final HumanPlaySpellAbility req = new HumanPlaySpellAbility(controller, sa, new CostPayment(sa.getPayCosts(), sa));

            req.playAbility(!useOldTargets, false, true);
        }
        else if (payManaCostIfNeeded(controller, player, sa)) {
            AbilityUtils.resolve(sa);
        }
    }

    // ------------------------------------------------------------------------

    private static int getAmountFromPart(CostPart part, Card source, SpellAbility sourceAbility) {
        String amountString = part.getAmount();
        return StringUtils.isNumeric(amountString) ? Integer.parseInt(amountString) : AbilityUtils.calculateAmount(source, amountString, sourceAbility);
    }

    /**
     * TODO: Write javadoc for this method.
     * @param part
     * @param source
     * @param sourceAbility
     * @return
     */
    private static int getAmountFromPartX(CostPart part, Card source, SpellAbility sourceAbility) {
        String amountString = part.getAmount();
        // Probably should just be -
        return AbilityUtils.calculateAmount(source, amountString, sourceAbility);
    }

    /**
     * <p>
     * payCostDuringAbilityResolve.
     * </p>
     *
     * @param cost
     *            a {@link forge.game.cost.Cost} object.
     * @param sourceAbility TODO
     */
    public static boolean payCostDuringAbilityResolve(final PlayerControllerHuman controller, final Player p, final Card source, final Cost cost, SpellAbility sourceAbility, String prompt) {
        // Only human player pays this way
        Card current = null; // Used in spells with RepeatEach effect to distinguish cards, Cut the Tethers
        if (sourceAbility.hasParam("ShowCurrentCard")) {
            current = Iterables.getFirst(AbilityUtils.getDefinedCards(source, sourceAbility.getParam("ShowCurrentCard"), sourceAbility), null);
        }

        final List<CostPart> parts = CostAdjustment.adjust(cost, sourceAbility).getCostParts();
        final List<CostPart> remainingParts = new ArrayList<CostPart>(parts);
        CostPart costPart = null;
        if (!parts.isEmpty()) {
            costPart = parts.get(0);
        }
        String orString = prompt == null ? sourceAbility.getStackDescription().trim() : "";
        if (!orString.isEmpty()) {
            if (sourceAbility.hasParam("UnlessSwitched")) {
                orString = TextUtil.concatWithSpace(" (if you do:", orString, ")");
            } else {
                orString = TextUtil.concatWithSpace(" (or:", orString, ")");
            }
        }

        if (parts.isEmpty() || (costPart.getAmount().equals("0") && parts.size() < 2)) {
            return p.getController().confirmPayment(costPart, "Do you want to pay {0}?" + orString, sourceAbility);
        }
        // 0 mana costs were slipping through because CostPart.getAmount returns 1
        else if (costPart instanceof CostPartMana && parts.size() < 2) {
            if (((CostPartMana) costPart).getManaToPay().isZero()) {
                return p.getController().confirmPayment(costPart, "Do you want to pay {0}?" + orString, sourceAbility);
            }
        }

        final HumanCostDecision hcd = new HumanCostDecision(controller, p, sourceAbility, source);
        boolean mandatory = cost.isMandatory();
        
        //the following costs do not need inputs
        for (CostPart part : parts) {
            boolean mayRemovePart = true;

            if (part instanceof CostPayLife) {
                final int amount = getAmountFromPart(part, source, sourceAbility);
                if (!p.canPayLife(amount)) {
                    return false;
                }

                if (!p.getController().confirmPayment(part, "Do you want to pay " + amount + " life?" + orString, sourceAbility)) {
                    return false;
                }

                p.payLife(amount, null);
            }
            else if (part instanceof CostDraw) {
                final int amount = getAmountFromPart(part, source, sourceAbility);
                List<Player> res = new ArrayList<Player>();
                String type = part.getType();
                for (Player player : p.getGame().getPlayers()) {
                    if (player.isValid(type, p, source, sourceAbility) && player.canDraw()) {
                        res.add(player);
                    }
                }

                if (res.isEmpty()) {
                    return false;
                }

                StringBuilder sb = new StringBuilder("Do you want to ");
                sb.append(res.contains(p) ? "" : "let that player ");
                sb.append("draw " + Lang.nounWithAmount(amount, " card") + "?" + orString);

                if (!p.getController().confirmPayment(part, sb.toString(), sourceAbility)) {
                    return false;
                }

                for (Player player : res) {
                    player.drawCards(amount);
                }
            }
            else if (part instanceof CostGainLife) {
                PaymentDecision pd = part.accept(hcd);
                
                if (pd == null)
                    return false;
                else
                    part.payAsDecided(p, pd, sourceAbility);
            }
            else if (part instanceof CostAddMana) {
                String desc = part.toString();
                desc = desc.substring(0, 1).toLowerCase() + desc.substring(1);

                if (!p.getController().confirmPayment(part, "Do you want to "+ desc + "?" + orString, sourceAbility)) {
                    return false;
                }
                PaymentDecision pd = part.accept(hcd);
                
                if (pd == null)
                    return false;
                else
                    part.payAsDecided(p, pd, sourceAbility);
            }
            else if (part instanceof CostMill) {
                final int amount = getAmountFromPart(part, source, sourceAbility);
                final CardCollectionView list = p.getCardsIn(ZoneType.Library);
                if (list.size() < amount) { return false; }
                if (!p.getController().confirmPayment(part, "Do you want to mill " + amount + " card" + (amount == 1 ? "" : "s") + "?" + orString, sourceAbility)) {
                    return false;
                }
                CardCollectionView listmill = p.getCardsIn(ZoneType.Library, amount);
                ((CostMill) part).executePayment(sourceAbility, listmill);
            }
            else if (part instanceof CostFlipCoin) {
                final int amount = getAmountFromPart(part, source, sourceAbility);
                if (!p.getController().confirmPayment(part, "Do you want to flip " + amount + " coin" + (amount == 1 ? "" : "s") + "?" + orString, sourceAbility)) {
                    return false;
                }
                final int n = FlipCoinEffect.getFilpMultiplier(p);
                for (int i = 0; i < amount; i++) {
                    FlipCoinEffect.flipCoinCall(p, sourceAbility, n);
                }
            }
            else if (part instanceof CostDamage) {
                int amount = getAmountFromPartX(part, source, sourceAbility);
                if (!p.canPayLife(amount)) {
                    return false;
                }

                if (!p.getController().confirmPayment(part, "Do you want " + source + " to deal " + amount + " damage to you?", sourceAbility)) {
                    return false;
                }
                CardDamageMap damageMap = new CardDamageMap();
                CardDamageMap preventMap = new CardDamageMap();

                p.addDamage(amount, source, damageMap, preventMap);

                preventMap.triggerPreventDamage(false);
                damageMap.triggerDamageDoneOnce(false);
            }
            else if (part instanceof CostPutCounter) {
                CounterType counterType = ((CostPutCounter) part).getCounter();
                int amount = getAmountFromPartX(part, source, sourceAbility);
                if (part.payCostFromSource()) {
                    if (!source.canReceiveCounters(counterType)) {
                        String message = TextUtil.concatNoSpace("Won't be able to pay upkeep for ", source.toString(), " but it can't have ", counterType.getName(), " counters put on it.");
                        p.getGame().getGameLog().add(GameLogEntryType.STACK_RESOLVE, message);
                        return false;
                    }

                    if (!p.getController().confirmPayment(part, "Do you want to put " + Lang.nounWithAmount(amount, counterType.getName() + " counter") + " on " + source + "?", sourceAbility)) {
                        return false;
                    }

                    source.addCounter(counterType, amount, source, false);
                }
                else {
                    CardCollectionView list = p.getGame().getCardsIn(ZoneType.Battlefield);
                    list = CardLists.getValidCards(list, part.getType().split(";"), p, source, sourceAbility);
                    if (list.isEmpty()) { return false; }
                    if (!p.getController().confirmPayment(part, "Do you want to put " + Lang.nounWithAmount(amount, counterType.getName() + " counter") + " on " + part.getTypeDescription() + "?", sourceAbility)) {
                        return false;
                    }
                    while (amount > 0) {
                        InputSelectCardsFromList inp = new InputSelectCardsFromList(controller, 1, 1, list, sourceAbility);
                        inp.setMessage("Select a card to add a counter to");
                        inp.setCancelAllowed(true);
                        inp.showAndWait();
                        if (inp.hasCancelled()) {
                            continue;
                        }
                        Card selected = inp.getFirstSelected();
                        selected.addCounter(counterType, 1, source, false);
                        amount--;
                    }
                }
            }
            else if (part instanceof CostRemoveCounter) {
                CounterType counterType = ((CostRemoveCounter) part).counter;
                int amount = getAmountFromPartX(part, source, sourceAbility);

                if (!part.canPay(sourceAbility, p)) {
                    return false;
                }

                if (!mandatory) {
                    if (!p.getController().confirmPayment(part, "Do you want to remove " + Lang.nounWithAmount(amount, counterType.getName() + " counter") + " from " + source + "?",sourceAbility)) {
                        return false;
                    }
                }

                source.subtractCounter(counterType, amount);
            }
            else if (part instanceof CostRemoveAnyCounter) {
                int amount = getAmountFromPartX(part, source, sourceAbility);
                CardCollectionView list = p.getCardsIn(ZoneType.Battlefield);
                int allCounters = 0;
                for (Card c : list) {
                    final Map<CounterType, Integer> tgtCounters = c.getCounters();
                    for (Integer value : tgtCounters.values()) {
                        allCounters += value;
                    }
                }
                if (allCounters < amount) { return false; }

                if (!mandatory) {
                    if (!p.getController().confirmPayment(part, "Do you want to remove counters from " + part.getDescriptiveType() + " ?",sourceAbility)) {
                        return false;
                    }
                }

                list = CardLists.getValidCards(list, part.getType().split(";"), p, source, sourceAbility);
                while (amount > 0) {
                    final CounterType counterType;
                    list = CardLists.filter(list, new Predicate<Card>() {
                        @Override
                        public boolean apply(final Card card) {
                            return card.hasCounters();
                        }
                    });
                    if (list.isEmpty()) { return false; }
                    InputSelectCardsFromList inp = new InputSelectCardsFromList(controller, 1, 1, list, sourceAbility);
                    inp.setMessage("Select a card to remove a counter");
                    inp.setCancelAllowed(true);
                    inp.showAndWait();
                    if (inp.hasCancelled()) {
                        continue;
                    }
                    Card selected = inp.getFirstSelected();
                    final Map<CounterType, Integer> tgtCounters = selected.getCounters();
                    final List<CounterType> typeChoices = new ArrayList<CounterType>();
                    for (CounterType key : tgtCounters.keySet()) {
                        if (tgtCounters.get(key) > 0) {
                            typeChoices.add(key);
                        }
                    }
                    if (typeChoices.size() > 1) {
                        String cprompt = "Select type counters to remove";
                        counterType = controller.getGui().one(cprompt, typeChoices);
                    }
                    else {
                        counterType = typeChoices.get(0);
                    }
                    selected.subtractCounter(counterType, 1);
                    amount--;
                }
            }
            else if (part instanceof CostExile) {
                final CardCollection exiledList = new CardCollection();
                ZoneType from = ZoneType.Graveyard;
                if ("All".equals(part.getType())) {
                    if (!p.getController().confirmPayment(part, "Do you want to exile all cards in your graveyard?", sourceAbility)) {
                        return false;
                    }

                    CardCollection cards = new CardCollection(p.getCardsIn(ZoneType.Graveyard));
                    for (final Card card : cards) {
                        exiledList.add(p.getGame().getAction().exile(card, null));
                    }
                }
                else {
                    CostExile costExile = (CostExile) part;
                    from = costExile.getFrom();
                    List<Card> list = CardLists.getValidCards(p.getCardsIn(from), part.getType().split(";"), p, source, sourceAbility);
                    final int nNeeded = getAmountFromPart(costPart, source, sourceAbility);
                    if (list.size() < nNeeded) {
                        return false;
                    }
                    if (from == ZoneType.Library) {
                        if (!p.getController().confirmPayment(part, "Do you want to exile " + nNeeded +
                                " card" + (nNeeded == 1 ? "" : "s") + " from your library?", sourceAbility)) {
                            return false;
                        }
                        list = list.subList(0, nNeeded);
                        for (Card c : list) {
                            exiledList.add(p.getGame().getAction().exile(c, null));
                        }
                    } else {
                        // replace this with input
                        for (int i = 0; i < nNeeded; i++) {
                            final Card c = p.getGame().getCard(SGuiChoose.oneOrNone("Exile from " + from, CardView.getCollection(list)));
                            if (c == null) {
                                return false;
                            }

                            list.remove(c);
                            exiledList.add(p.getGame().getAction().exile(c, null));
                        }
                    }
                }

                if (!exiledList.isEmpty()) {
                    final HashMap<String, Object> runParams = new HashMap<String, Object>();
                    final Map<ZoneType, CardCollection> triggerList = Maps.newEnumMap(ZoneType.class);
                    triggerList.put(from, exiledList);
                    runParams.put("Cards", triggerList);
                    runParams.put("Destination", ZoneType.Exile);
                    p.getGame().getTriggerHandler().runTrigger(TriggerType.ChangesZoneAll, runParams, false);
                }
            }
            else if (part instanceof CostPutCardToLib) {
                int amount = Integer.parseInt(((CostPutCardToLib) part).getAmount());
                final ZoneType from = ((CostPutCardToLib) part).getFrom();
                final boolean sameZone = ((CostPutCardToLib) part).isSameZone();
                CardCollectionView listView;
                if (sameZone) {
                    listView = p.getGame().getCardsIn(from);
                }
                else {
                    listView = p.getCardsIn(from);
                }
                CardCollection list = CardLists.getValidCards(listView, part.getType().split(";"), p, source, sourceAbility);

                if (sameZone) { // Jotun Grunt
                    FCollectionView<Player> players = p.getGame().getPlayers();
                    List<Player> payableZone = new ArrayList<Player>();
                    for (Player player : players) {
                        CardCollectionView enoughType = CardLists.filter(list, CardPredicates.isOwner(player));
                        if (enoughType.size() < amount) {
                            list.removeAll(enoughType);
                        } else {
                            payableZone.add(player);
                        }
                    }
                    Player chosen = controller.getGame().getPlayer(SGuiChoose.oneOrNone(TextUtil.concatNoSpace("Put cards from whose ", from.toString(), "?"), PlayerView.getCollection(payableZone)));
                    if (chosen == null) {
                        return false;
                    }

                    List<Card> typeList = CardLists.filter(list, CardPredicates.isOwner(chosen));

                    for (int i = 0; i < amount; i++) {
                        if (typeList.isEmpty()) {
                            return false;
                        }

                        final Card c = p.getGame().getCard(SGuiChoose.oneOrNone("Put cards to Library", CardView.getCollection(typeList)));

                        if (c != null) {
                            typeList.remove(c);
                            p.getGame().getAction().moveToLibrary(c, Integer.parseInt(((CostPutCardToLib) part).getLibPos()), null);
                        }
                        else {
                            return false;
                        }
                    }
                }
                else if (from == ZoneType.Hand) { // Tainted Specter
                    boolean hasPaid = payCostPart(controller, sourceAbility, (CostPartWithList)part, amount, list, "put into library." + orString);
                    if (!hasPaid) {
                        return false;
                    }
                }
                return true;
            }
            else if (part instanceof CostSacrifice) {
                int amount = Integer.parseInt(((CostSacrifice)part).getAmount());
                CardCollectionView list = CardLists.getValidCards(p.getCardsIn(ZoneType.Battlefield), part.getType().split(";"), p, source, sourceAbility);
                boolean hasPaid = payCostPart(controller, sourceAbility, (CostPartWithList)part, amount, list, "sacrifice." + orString);
                if (!hasPaid) { return false; }
            }
            else if (part instanceof CostGainControl) {
                int amount = Integer.parseInt(((CostGainControl)part).getAmount());
                CardCollectionView list = CardLists.getValidCards(p.getGame().getCardsIn(ZoneType.Battlefield), part.getType(), p, source);
                boolean hasPaid = payCostPart(controller, sourceAbility, (CostPartWithList)part, amount, list, "gain control." + orString);
                if (!hasPaid) { return false; }
            }
            else if (part instanceof CostReturn) {
                CardCollectionView list = CardLists.getValidCards(p.getCardsIn(ZoneType.Battlefield), part.getType(), p, source);
                int amount = getAmountFromPartX(part, source, sourceAbility);
                boolean hasPaid = payCostPart(controller, sourceAbility, (CostPartWithList)part, amount, list, "return to hand." + orString);
                if (!hasPaid) { return false; }
            }
            else if (part instanceof CostDiscard) {
                if ("Hand".equals(part.getType())) {
                    if (!p.getController().confirmPayment(part, "Do you want to discard your hand?", sourceAbility)) {
                        return false;
                    }

                    CardCollection cards = new CardCollection(p.getCardsIn(ZoneType.Hand));
                    for (final Card card : cards) {
                        p.discard(card, sourceAbility);
                    }
                } else {
                    CardCollectionView list = CardLists.getValidCards(p.getCardsIn(ZoneType.Hand), part.getType(), p, source);
                    int amount = getAmountFromPartX(part, source, sourceAbility);
                    boolean hasPaid = payCostPart(controller, sourceAbility, (CostPartWithList)part, amount, list, "discard." + orString);
                    if (!hasPaid) { return false; }
                }
            }
            else if (part instanceof CostReveal) {
                CardCollectionView list = CardLists.getValidCards(p.getCardsIn(ZoneType.Hand), part.getType(), p, source);
                int amount = getAmountFromPartX(part, source, sourceAbility);
                boolean hasPaid = payCostPart(controller, sourceAbility, (CostPartWithList)part, amount, list, "reveal." + orString);
                if (!hasPaid) { return false; }
            }
            else if (part instanceof CostTapType) {
                CardCollectionView list = CardLists.getValidCards(p.getCardsIn(ZoneType.Battlefield), part.getType(), p, source);
                list = CardLists.filter(list, Presets.UNTAPPED);
                int amount = getAmountFromPartX(part, source, sourceAbility);
                boolean hasPaid = payCostPart(controller, sourceAbility, (CostPartWithList)part, amount, list, "tap." + orString);
                if (!hasPaid) { return false; }
            }
            else if (part instanceof CostPartMana) {
                if (!((CostPartMana) part).getManaToPay().isZero()) { // non-zero costs require input
                    mayRemovePart = false;
                }
            }
            else if (part instanceof CostPayEnergy) {
                CounterType counterType = CounterType.ENERGY;
                int amount = getAmountFromPartX(part, source, sourceAbility);

                if (!part.canPay(sourceAbility, p)) {
                    return false;
                }

                if (!mandatory) {
                    if (!p.getController().confirmPayment(part, "Do you want to spend " + Lang.nounWithAmount(amount, counterType.getName() + " counter") + "?",sourceAbility)) {
                        return false;
                    }
                }

                p.payEnergy(amount, source);
            }

            else {
                throw new RuntimeException("GameActionUtil.payCostDuringAbilityResolve - An unhandled type of cost was met: " + part.getClass());
            }

            if (mayRemovePart) {
                remainingParts.remove(part);
            }
        }

        if (remainingParts.isEmpty()) {
            return true;
        }
        if (remainingParts.size() > 1) {
            throw new RuntimeException("GameActionUtil.payCostDuringAbilityResolve - Too many payment types - " + source);
        }
        costPart = remainingParts.get(0);
        // check this is a mana cost
        if (!(costPart instanceof CostPartMana)) {
            throw new RuntimeException("GameActionUtil.payCostDuringAbilityResolve - The remaining payment type is not Mana.");
        }

        if (prompt == null) {
            String promptCurrent = current == null ? "" : "Current Card: " + current;
            prompt = source + "\n" + promptCurrent;
        }
        
        sourceAbility.clearManaPaid();
        boolean paid = p.getController().payManaCost(cost.getCostMana(), sourceAbility, prompt, false);
        if (!paid) {
            p.getManaPool().refundManaPaid(sourceAbility);
        }
        return paid;
    }

    private static boolean payCostPart(final PlayerControllerHuman controller, SpellAbility sourceAbility, CostPartWithList cpl, int amount, CardCollectionView list, String actionName) {
        if (list.size() < amount) { return false; } // unable to pay (not enough cards)

        InputSelectCardsFromList inp = new InputSelectCardsFromList(controller, amount, amount, list, sourceAbility);
        inp.setMessage("Select %d " + cpl.getDescriptiveType() + " card(s) to " + actionName);
        inp.setCancelAllowed(true);

        inp.showAndWait();
        if (inp.hasCancelled() || inp.getSelected().size() != amount) {
            return false;
        }

        for (Card c : inp.getSelected()) {
            cpl.executePayment(sourceAbility, c);
        }
        if (sourceAbility != null) {
            cpl.reportPaidCardsTo(sourceAbility);
        }
        return true;
    }
    

    private static boolean handleOfferingConvokeAndDelve(final SpellAbility ability, CardCollection cardsToDelve, boolean manaInputCancelled) {
        if (!manaInputCancelled && !cardsToDelve.isEmpty()) {
            Card hostCard = ability.getHostCard();
            final Game game = hostCard.getGame();

            final CardCollection delved = new CardCollection();
            final Map<ZoneType, CardCollection> triggerList = Maps.newEnumMap(ZoneType.class);

            for (final Card c : cardsToDelve) {
                hostCard.addDelved(c);
                delved.add(game.getAction().exile(c, null));
            }

            if (!delved.isEmpty()) {
                triggerList.put(ZoneType.Graveyard, delved);
                final Map<String, Object> runParams = Maps.newHashMap();
                runParams.put("Cards", triggerList);
                runParams.put("Destination", ZoneType.Exile);
                game.getTriggerHandler().runTrigger(TriggerType.ChangesZoneAll, runParams, false);
            }
        }
        if (ability.isOffering() && ability.getSacrificedAsOffering() != null) {
            final Card offering = ability.getSacrificedAsOffering();
            offering.setUsedToPay(false);
            if (!manaInputCancelled) {
                ability.getHostCard().getGame().getAction().sacrifice(offering, ability);
            }
            ability.resetSacrificedAsOffering();
        }
        if (ability.isEmerge() && ability.getSacrificedAsEmerge() != null) {
            final Card emerge = ability.getSacrificedAsEmerge();
            emerge.setUsedToPay(false);
            if (!manaInputCancelled) {
                ability.getHostCard().getGame().getAction().sacrifice(emerge, ability);
            }
            ability.resetSacrificedAsEmerge();
        }
        if (ability.getTappedForConvoke() != null) {
            for (final Card c : ability.getTappedForConvoke()) {
                c.setTapped(false);
                if (!manaInputCancelled) {
                    c.tap();
                }
            }
            ability.clearTappedForConvoke();
        }
        return !manaInputCancelled;
    }
    
    public static boolean payManaCost(final PlayerControllerHuman controller, final ManaCost realCost, final CostPartMana mc, final SpellAbility ability, final Player activator, String prompt, boolean isActivatedSa) {
        final Card source = ability.getHostCard();
        ManaCostBeingPaid toPay = new ManaCostBeingPaid(realCost, mc.getRestiction());

        String xInCard = source.getSVar("X");
        if (mc.getAmountOfX() > 0 && !"Count$xPaid".equals(xInCard)) { // announce X will overwrite whatever was in card script
            int xPaid = AbilityUtils.calculateAmount(source, "X", ability);
            toPay.setXManaCostPaid(xPaid, ability.getParam("XColor"));
            source.setXManaCostPaid(xPaid);
        }
        else if (source.getXManaCostPaid() > 0) { //ensure pre-announced X value retained
            toPay.setXManaCostPaid(source.getXManaCostPaid(), ability.getParam("XColor"));
        }

        int timesMultikicked = source.getKickerMagnitude();
        if (timesMultikicked > 0 && ability.isAnnouncing("Multikicker")) {
            ManaCost mkCost = ability.getMultiKickerManaCost();
            for (int i = 0; i < timesMultikicked; i++) {
                toPay.addManaCost(mkCost);
            }
        }

        int timesPseudokicked = source.getPseudoKickerMagnitude();
        if (timesPseudokicked > 0 && ability.isAnnouncing("Pseudo-multikicker")) {
            ManaCost mkCost = ability.getMultiKickerManaCost();
            for (int i = 0; i < timesPseudokicked; i++) {
                toPay.addManaCost(mkCost);
            }
        }

        Integer replicate = ability.getSVarInt("Replicate");
        if (replicate != null) {
            ManaCost rCost = source.getManaCost();
            for (int i = 0; i < replicate; i++) {
                toPay.addManaCost(rCost);
            }
        }

        CardCollection cardsToDelve = new CardCollection();
        if (isActivatedSa) {
            CostAdjustment.adjust(toPay, ability, cardsToDelve, false);
        }

        Card offering = null;
        Card emerge = null;

        InputPayMana inpPayment;
        if (ability.isOffering()) {
            if (ability.getSacrificedAsOffering() == null) {
                System.out.println("Sacrifice input for Offering cancelled");
                return false;
            } else {
                offering = ability.getSacrificedAsOffering();
            }
        }
        if (ability.isEmerge()) {
            if (ability.getSacrificedAsEmerge() == null) {
                System.out.println("Sacrifice input for Emerge cancelled");
                return false;
            } else {
                emerge = ability.getSacrificedAsEmerge();
            }
        }
        if (!toPay.isPaid()) {
            // Input is somehow clearing out the offering card?
            inpPayment = new InputPayManaOfCostPayment(controller, toPay, ability, activator);
            inpPayment.setMessagePrefix(prompt);
            inpPayment.showAndWait();
            if (!inpPayment.isPaid()) {
                return handleOfferingConvokeAndDelve(ability, cardsToDelve, true);
            }

            source.setXManaCostPaidByColor(toPay.getXManaCostPaidByColor());
            source.setColorsPaid(toPay.getColorsPaid());
            source.setSunburstValue(toPay.getSunburst());
        }

        // Handle convoke and offerings
        if (ability.isOffering()) {
            if (ability.getSacrificedAsOffering() == null && offering != null) {
                ability.setSacrificedAsOffering(offering);
            }
            if (ability.getSacrificedAsOffering() != null) {
                System.out.println("Finishing up Offering");
                offering.setUsedToPay(false);
                activator.getGame().getAction().sacrifice(offering, ability);
                ability.resetSacrificedAsOffering();
            }
        }
        if (ability.isEmerge()) {
            if (ability.getSacrificedAsEmerge() == null && emerge != null) {
                ability.setSacrificedAsEmerge(emerge);
            }
            if (ability.getSacrificedAsEmerge() != null) {
                System.out.println("Finishing up Emerge");
                emerge.setUsedToPay(false);
                activator.getGame().getAction().sacrifice(emerge, ability);
                ability.resetSacrificedAsEmerge();
            }
        }
        if (ability.getTappedForConvoke() != null) {
            for (final Card c : ability.getTappedForConvoke()) {
                c.setTapped(false);
                c.tap();
            }
            ability.clearTappedForConvoke();
        }
        return handleOfferingConvokeAndDelve(ability, cardsToDelve, false);
    }
}
