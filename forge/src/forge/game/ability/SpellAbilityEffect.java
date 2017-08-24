package forge.game.ability;

import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;

import forge.card.CardType;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardFactoryUtil;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.replacement.ReplacementLayer;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.Lang;
import forge.util.collect.FCollection;

/**
 * <p>
 * AbilityFactory_AlterLife class.
 * </p>
 * 
 * @author Forge
 * @version $Id: AbilityFactoryAlterLife.java 17656 2012-10-22 19:32:56Z Max mtg $
 */

public abstract class SpellAbilityEffect {

    public abstract void resolve(SpellAbility sa);

    protected String getStackDescription(final SpellAbility sa) {
        // Unless overriden, let the spell description also be the stack description
        return sa.getDescription();
    }

    protected static final void resolveSubAbility(final SpellAbility sa) {
        // if mana production has any type of SubAbility, undoable=false
        final AbilitySub abSub = sa.getSubAbility();
        if (abSub != null) {
            sa.setUndoable(false);
            AbilityUtils.resolve(abSub);
        }
    }

    /**
     * Returns this effect description with needed prelude and epilogue.
     * @param params
     * @param sa
     * @return
     */
    public final String getStackDescriptionWithSubs(final Map<String, String> params, final SpellAbility sa) {
        StringBuilder sb = new StringBuilder();

        if (sa.getApi() != ApiType.PermanentCreature && sa.getApi() != ApiType.PermanentNoncreature) {
            // prelude for when this is root ability
            if (!(sa instanceof AbilitySub)) {
                sb.append(sa.getHostCard()).append(" -");
            }
            sb.append(" ");
        }

        // Own description
        String stackDesc = params.get("StackDescription");
        if (stackDesc != null) {
            if ("SpellDescription".equalsIgnoreCase(stackDesc)) { // by typing "none" they want to suppress output
                if (params.get("SpellDescription") != null) {
                    sb.append(params.get("SpellDescription").replace("CARDNAME", sa.getHostCard().getName()));
                }
                if (sa.getTargets() != null && !sa.getTargets().getTargets().isEmpty()) {
                    sb.append(" (Targeting: " + sa.getTargets().getTargets() + ")");
                }
            } else if (!"None".equalsIgnoreCase(stackDesc)) { // by typing "none" they want to suppress output
                makeSpellDescription(sa, sb, stackDesc);
            }
        } else {
            final String conditionDesc = sa.getParam("ConditionDescription");
            final String baseDesc = this.getStackDescription(sa);
            if (conditionDesc != null) {
                sb.append(conditionDesc).append(" ");
            } 
            sb.append(baseDesc);
        }

        // only add to StackDescription if its not a Permanent Spell
        if (sa.getApi() != ApiType.PermanentCreature && sa.getApi() != ApiType.PermanentNoncreature) {
            // This includes all subAbilities
            final AbilitySub abSub = sa.getSubAbility();
            if (abSub != null) {
                sb.append(abSub.getStackDescription());
            }
        }

        if (sa.hasParam("Announce")) {
            String svar = sa.getParam("Announce");
            int amount = CardFactoryUtil.xCount(sa.getHostCard(), sa.getSVar(svar));
            sb.append(String.format(" (%s=%d)", svar, amount));
        } else{
            if (sa.getPayCosts() != null && sa.getPayCosts().getCostMana() != null &&
                    sa.getPayCosts().getCostMana().getAmountOfX() > 0) {
                int amount = sa.getHostCard().getXManaCostPaid();
                sb.append(String.format(" (%s=%d)", "X", amount));
            }
        }

        return sb.toString();
    }

    /**
     * Append the description of a {@link SpellAbility} to a
     * {@link StringBuilder}.
     * 
     * @param sa
     *            a {@link SpellAbility}.
     * @param sb
     *            a {@link StringBuilder}.
     * @param stackDesc
     *            the stack description of sa, formatted so that text appearing
     *            between braces <code>{ }</code> is replaced with defined
     *            {@link Player}, {@link SpellAbility}, and {@link Card}
     *            objects.
     */
    private static void makeSpellDescription(final SpellAbility sa, final StringBuilder sb, final String stackDesc) {
        final StringTokenizer st = new StringTokenizer(stackDesc, "{}", true);
        boolean isPlainText = true;

        while (st.hasMoreTokens()) {
            final String t = st.nextToken();
            if ("{".equals(t)) { isPlainText = false; continue; }
            if ("}".equals(t)) { isPlainText = true; continue; }

            if (isPlainText) {
                sb.append(t.replace("CARDNAME", sa.getHostCard().getName()));
            } else {
                final List<? extends GameObject> objs;
                if (t.startsWith("p:")) {
                    objs = AbilityUtils.getDefinedPlayers(sa.getHostCard(), t.substring(2), sa);
                } else if (t.startsWith("s:")) {
                    objs = AbilityUtils.getDefinedSpellAbilities(sa.getHostCard(), t.substring(2), sa);
                } else if (t.startsWith("c:")) {
                    objs = AbilityUtils.getDefinedCards(sa.getHostCard(), t.substring(2), sa);
                } else {
                    objs = AbilityUtils.getDefinedObjects(sa.getHostCard(), t, sa);
                }

                sb.append(StringUtils.join(objs, ", "));
            }
        }
    }

    // Target/defined methods
    // Cards
    protected final static CardCollection getTargetCards(final SpellAbility sa) {                                       return getCards(false, "Defined",    sa); }
    protected final static CardCollection getTargetCards(final SpellAbility sa, final String definedParam) {            return getCards(false, definedParam, sa); }
    protected final static CardCollection getDefinedCardsOrTargeted(final SpellAbility sa) {                            return getCards(true,  "Defined",    sa); }
    protected final static CardCollection getDefinedCardsOrTargeted(final SpellAbility sa, final String definedParam) { return getCards(true,  definedParam, sa); }

    private static CardCollection getCards(final boolean definedFirst, final String definedParam, final SpellAbility sa) {
        final boolean useTargets = sa.usesTargeting() && (!definedFirst || !sa.hasParam(definedParam))
                && sa.getTargets() != null && (sa.getTargets().isTargetingAnyCard() || sa.getTargets().getTargets().isEmpty());
        return useTargets ? new CardCollection(sa.getTargets().getTargetCards()) 
                : AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam(definedParam), sa);
    }

    // Players
    protected final static FCollection<Player> getTargetPlayers(final SpellAbility sa) {                                       return getPlayers(false, "Defined",    sa); }
    protected final static FCollection<Player> getTargetPlayers(final SpellAbility sa, final String definedParam) {            return getPlayers(false, definedParam, sa); }
    protected final static FCollection<Player> getDefinedPlayersOrTargeted(final SpellAbility sa ) {                           return getPlayers(true,  "Defined",    sa); }
    protected final static FCollection<Player> getDefinedPlayersOrTargeted(final SpellAbility sa, final String definedParam) { return getPlayers(true,  definedParam, sa); }

    private static FCollection<Player> getPlayers(final boolean definedFirst, final String definedParam, final SpellAbility sa) {
        final boolean useTargets = sa.usesTargeting() && (!definedFirst || !sa.hasParam(definedParam));
        return useTargets ? new FCollection<Player>(sa.getTargets().getTargetPlayers()) 
                : AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam(definedParam), sa);
    }

    // Spells
    protected final static List<SpellAbility> getTargetSpells(final SpellAbility sa) {                                       return getSpells(false, "Defined",    sa); }
    protected final static List<SpellAbility> getTargetSpells(final SpellAbility sa, final String definedParam) {            return getSpells(false, definedParam, sa); }
    protected final static List<SpellAbility> getDefinedSpellsOrTargeted(final SpellAbility sa, final String definedParam) { return getSpells(true,  definedParam, sa); }

    private static List<SpellAbility> getSpells(final boolean definedFirst, final String definedParam, final SpellAbility sa) {
        final boolean useTargets = sa.usesTargeting() && (!definedFirst || !sa.hasParam(definedParam));
        return useTargets ? Lists.newArrayList(sa.getTargets().getTargetSpells()) 
                : AbilityUtils.getDefinedSpellAbilities(sa.getHostCard(), sa.getParam(definedParam), sa);
    }

    // Targets of unspecified type
    protected final static List<GameObject> getTargets(final SpellAbility sa) {                                return getTargetables(false, "Defined",    sa); }
    protected final static List<GameObject> getTargets(final SpellAbility sa, final String definedParam) {     return getTargetables(false, definedParam, sa); }
    protected final static List<GameObject> getDefinedOrTargeted(SpellAbility sa, final String definedParam) { return getTargetables(true,  definedParam, sa); }

    private static List<GameObject> getTargetables(final boolean definedFirst, final String definedParam, final SpellAbility sa) {
        final boolean useTargets = sa.usesTargeting() && (!definedFirst || !sa.hasParam(definedParam));
        return useTargets ? Lists.newArrayList(sa.getTargets().getTargets()) 
                : AbilityUtils.getDefinedObjects(sa.getHostCard(), sa.getParam(definedParam), sa);
    }
    

    protected static void registerDelayedTrigger(final SpellAbility sa, String location, final List<Card> crds) {
        boolean intrinsic = sa.isIntrinsic();
        boolean your = location.startsWith("Your");
        boolean combat = location.endsWith("Combat");

        if (your) {
            location = location.substring("Your".length());
        }
        if (combat) {
            location = location.substring(0, location.length() - "Combat".length());
        }

        StringBuilder delTrig = new StringBuilder();
            delTrig.append("Mode$ Phase | Phase$ ");
            delTrig.append(combat ? "EndCombat "  : "End Of Turn ");
 
            if (your) {
                delTrig.append("| ValidPlayer$ You ");
            }
            delTrig.append("| TriggerDescription$ " + location + " ");
            delTrig.append(Lang.joinHomogenous(crds));
            delTrig.append(" at the ");
            if (combat) {
                delTrig.append("end of combat.");
            } else {
                delTrig.append("beginning of ");
                delTrig.append(your ? "your" : "the");
                delTrig.append(" next end step.");
            }
        final Trigger trig = TriggerHandler.parseTrigger(delTrig.toString(), sa.getHostCard(), intrinsic);
        for (final Card c : crds) {
            trig.addRemembered(c);
        }
        String trigSA = "";
        if (location.equals("Sacrifice")) {
            trigSA = "DB$ SacrificeAll | Defined$ DelayTriggerRemembered | Controller$ You";
        } else if (location.equals("Exile")) {
            trigSA = "DB$ ChangeZone | Defined$ DelayTriggerRemembered | Origin$ Battlefield | Destination$ Exile";
        } else if (location.equals("Destroy")) {
            trigSA = "DB$ Destroy | Defined$ DelayTriggerRemembered";
        }
        final SpellAbility newSa = AbilityFactory.getAbility(trigSA, sa.getHostCard());
        newSa.setIntrinsic(intrinsic);
        trig.setOverridingAbility(newSa);
        sa.getActivatingPlayer().getGame().getTriggerHandler().registerDelayedTrigger(trig);
    }
    
    protected static void addSelfTrigger(final SpellAbility sa, String location, final Card card) {
    	
    	String trigStr = "Mode$ Phase | Phase$ End of Turn | TriggerZones$ Battlefield " +
    	     "| TriggerDescription$ At the beginning of the end step, " + location.toLowerCase()  + " CARDNAME.";
    	
    	final Trigger trig = TriggerHandler.parseTrigger(trigStr, card, true);
    	
    	String trigSA = "";
        if (location.equals("Sacrifice")) {
            trigSA = "DB$ Sacrifice | SacValid$ Self";
        } else if (location.equals("Exile")) {
            trigSA = "DB$ ChangeZone | Origin$ Battlefield | Destination$ Exile | Defined$ Self";
        }
        trig.setOverridingAbility(AbilityFactory.getAbility(trigSA, card));
        card.addTrigger(trig);
    }
    
    protected static void addForgetOnMovedTrigger(final Card card, final String zone) {
        String trig = "Mode$ ChangesZone | ValidCard$ Card.IsRemembered | Origin$ " + zone + " | Destination$ Any | TriggerZones$ Command | Static$ True";
        String effect = "DB$ Pump | ForgetObjects$ TriggeredCard";
        final Trigger parsedTrigger = TriggerHandler.parseTrigger(trig, card, true);
        parsedTrigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
        final Trigger addedTrigger = card.addTrigger(parsedTrigger);
        addedTrigger.setIntrinsic(true);
    }

    protected static void addExileOnMovedTrigger(final Card card, final String zone) {
        String trig = "Mode$ ChangesZone | ValidCard$ Card.IsRemembered | Origin$ " + zone + " | Destination$ Any | TriggerZones$ Command | Static$ True";
        String effect = "DB$ ChangeZone | Defined$ Self | Origin$ Command | Destination$ Exile";
        final Trigger parsedTrigger = TriggerHandler.parseTrigger(trig, card, true);
        parsedTrigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
        final Trigger addedTrigger = card.addTrigger(parsedTrigger);
        addedTrigger.setIntrinsic(true);
    }

    protected static void addLeaveBattlefieldReplacement(final Card card, final SpellAbility sa, final String zone) {
        final Card host = sa.getHostCard();
        final Game game = card.getGame();
        final Card eff = createEffect(host, sa.getActivatingPlayer(), host.getName() + "'s Effect", host.getImageKey());

        addLeaveBattlefieldReplacement(eff, zone);

        eff.addRemembered(card);

        // Add forgot trigger
        addExileOnMovedTrigger(eff, "Battlefield");

        // Copy text changes
        if (sa.isIntrinsic()) {
            eff.copyChangedTextFrom(card);
        }

        eff.updateStateForView();

        // TODO: Add targeting to the effect so it knows who it's dealing with
        game.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
        game.getAction().moveTo(ZoneType.Command, eff, sa);
        game.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);
    }
    
    protected static void addLeaveBattlefieldReplacement(final Card eff, final String zone) {
        final String repeffstr = "Event$ Moved | ValidCard$ Card.IsRemembered "
                + "| Origin$ Battlefield | ExcludeDestination$ " + zone 
                + "| Description$ If Creature would leave the battlefield, "
                + " exile it instead of putting it anywhere else.";
        String effect = "DB$ ChangeZone | Defined$ ReplacedCard | Origin$ Battlefield | Destination$ " + zone;

        ReplacementEffect re = ReplacementHandler.parseReplacement(repeffstr, eff, true);
        re.setLayer(ReplacementLayer.Other);

        re.setOverridingAbility(AbilityFactory.getAbility(effect, eff));
        eff.addReplacementEffect(re);
    }
    
    // create a basic template for Effect to be used somewhere else
    protected static Card createEffect(final Card hostCard, final Player controller, final String name,
            final String image) {
        final Game game = hostCard.getGame();
        final Card eff = new Card(game.nextCardId(), game);
        eff.setTimestamp(game.getNextTimestamp());
        eff.setName(name);
        // if name includes emplem then it should be one
        eff.addType(name.endsWith("emblem") ? "Emblem" : "Effect");
        // add Planeswalker types into Emblem for fun
        if (name.endsWith("emblem") && hostCard.isPlaneswalker()) {
            for (final String type : hostCard.getType().getSubtypes()) {
                if (CardType.isAPlaneswalkerType(type)) {
                    eff.addType(type);
                }
            }
        }
        eff.setToken(true); // Set token to true, so when leaving play it gets nuked
        eff.setOwner(controller);

        eff.setImageKey(image);
        eff.setColor(hostCard.determineColor().getColor());
        eff.setImmutable(true);
        eff.setEffectSource(hostCard);

        return eff;
    }
}
