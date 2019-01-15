/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.game.player;

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.*;
import forge.LobbyPlayer;
import forge.card.MagicColor;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.DetachedCardEffect;
import forge.game.card.*;
import forge.game.card.CardPredicates.Presets;
import forge.game.event.*;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordCollection;
import forge.game.keyword.KeywordCollection.KeywordCollectionView;
import forge.game.keyword.KeywordsChange;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.replacement.ReplacementHandler;
import forge.game.replacement.ReplacementResult;
import forge.game.spellability.AbilityActivated;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.zone.PlayerZone;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.util.Aggregates;
import forge.util.Lang;
import forge.util.MyRandom;
import forge.util.TextUtil;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * <p>
 * Abstract Player class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class Player extends GameEntity implements Comparable<Player> {
    public static final List<ZoneType> ALL_ZONES = Collections.unmodifiableList(Arrays.asList(ZoneType.Battlefield,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Hand, ZoneType.Exile, ZoneType.Command, ZoneType.Ante,
            ZoneType.Sideboard, ZoneType.PlanarDeck, ZoneType.SchemeDeck));

    private final Map<Card, Integer> commanderDamage = Maps.newHashMap();

    private int life = 20;
    private int startingLife = 20;
    private final Map<Card, Integer> assignedDamage = Maps.newHashMap();
    private final Map<Card, Integer> assignedCombatDamage = Maps.newHashMap();
    private int spellsCastThisTurn = 0;
    private int spellsCastLastTurn = 0;
    private int landsPlayedThisTurn = 0;
    private int landsPlayedLastTurn = 0;
    private int investigatedThisTurn = 0;
    private int lifeLostThisTurn = 0;
    private int lifeLostLastTurn = 0;
    private int lifeGainedThisTurn = 0;
    private int numPowerSurgeLands;
    private int numLibrarySearchedOwn = 0; //The number of times this player has searched his library
    private int maxHandSize = 7;
    private int startingHandSize = 7;
    private boolean unlimitedHandSize = false;
    private Card lastDrawnCard = null;
    private String namedCard = "";
    private int numDrawnThisTurn = 0;
    private int numDrawnThisDrawStep = 0;
    private int numDiscardedThisTurn = 0;
    private int numCardsInHandStartedThisTurnWith = 0;

    private boolean revolt = false;

    private CardCollection sacrificedThisTurn = new CardCollection();

    private Map<CounterType, Integer> countersAddedtoPermThisTurn = Maps.newEnumMap(CounterType.class);
    
    /** A list of tokens not in play, but on their way.
     * This list is kept in order to not break ETB-replacement
     * on tokens. */
    private CardCollection inboundTokens = new CardCollection();

    private KeywordCollection keywords = new KeywordCollection();

    private Map<Card, DetachedCardEffect> staticAbilities = Maps.newHashMap();

    private Map<Long, KeywordsChange> changedKeywords = new ConcurrentSkipListMap<Long, KeywordsChange>();
    private ManaPool manaPool = new ManaPool(this);
    private GameEntity mustAttackEntity = null;
    private GameEntity mustAttackEntityThisTurn = null;
    private boolean attackedWithCreatureThisTurn = false;
    private boolean activateLoyaltyAbilityThisTurn = false;
    private boolean tappedLandForManaThisTurn = false;
    private int attackersDeclaredThisTurn = 0;

    private final Map<ZoneType, PlayerZone> zones = Maps.newEnumMap(ZoneType.class);

    private CardCollection currentPlanes = new CardCollection();
    private Set<String> prowl = Sets.newHashSet();

    private PlayerStatistics stats = new PlayerStatistics();
    private PlayerController controller;
    private PlayerController controllerCreator = null;
    private Player mindSlaveMaster = null;

    private int teamNumber = -1;
    private Card activeScheme = null;
    private List<Card> commanders = Lists.newArrayList();
    private Map<Card, Integer> commanderCast = Maps.newHashMap();
    private final Game game;
    private boolean triedToDrawFromEmptyLibrary = false;
    private boolean isPlayingExtraTrun = false;
    private CardCollection lostOwnership = new CardCollection();
    private CardCollection gainedOwnership = new CardCollection();
    private int numManaConversion = 0;

    private Card monarchEffect = null;
    private Card blessingEffect = null;

    private int extraTurnNum = 0;

    private final AchievementTracker achievementTracker = new AchievementTracker();
    private final PlayerView view;

    public Player(String name0, Game game0, final int id0) {
        super(id0);

        game = game0;
        for (final ZoneType z : Player.ALL_ZONES) {
            final PlayerZone toPut = z == ZoneType.Battlefield ? new PlayerZoneBattlefield(z, this) : new PlayerZone(z, this);
            zones.put(z, toPut);
        }

        view = new PlayerView(id0, game.getTracker());
        view.updateMaxHandSize(this);
        view.updateKeywords(this);
        setName(chooseName(name0));
        if (id0 >= 0) {
            game.addPlayer(id, this);
        }
    }

    public final AchievementTracker getAchievementTracker() {
        return achievementTracker;
    }

    public final PlayerOutcome getOutcome() {
        return stats.getOutcome();
    }

    private String chooseName(String originalName) {
        String nameCandidate = originalName;
        for (int i = 2; i <= 8; i++) { // several tries, not matter how many
            boolean haveDuplicates = false;
            for (Player p : game.getPlayers()) {
                if (p.getName().equals(nameCandidate)) {
                    haveDuplicates = true;
                    break;
                }
            }
            if (!haveDuplicates) {
                return nameCandidate;
            }
            nameCandidate = Lang.getOrdinal(i) + " " + originalName;
        }
        return nameCandidate;
    }

    @Override
    public Game getGame() {
        return game;
    }

    public final PlayerStatistics getStats() {
        return stats;
    }

    public final int getTeam() {
        return teamNumber;
    }
    public final void setTeam(int iTeam) {
        teamNumber = iTeam;
    }

    public boolean isArchenemy() {
        return getZone(ZoneType.SchemeDeck).size() > 0; //Only the archenemy has schemes.
    }

    public Card getActiveScheme() {
        return activeScheme;
    }

    public void setSchemeInMotion() {
        for (final Player p : game.getPlayers()) {
            if (p.hasKeyword("Schemes can't be set in motion this turn.")) {
                return;
            }
        }

        // Replacement effects
        final Map<String, Object> repRunParams = Maps.newHashMap();
        repRunParams.put("Event", "SetInMotion");
        repRunParams.put("Affected", this);

        if (game.getReplacementHandler().run(repRunParams) != ReplacementResult.NotReplaced) {
            return;
        }

        game.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
        activeScheme = getZone(ZoneType.SchemeDeck).get(0);
        // gameAction moveTo ?
        game.getAction().moveTo(ZoneType.Command, activeScheme, null, null);
        game.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);

        // Run triggers
        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("Scheme", activeScheme);
        game.getTriggerHandler().runTrigger(TriggerType.SetInMotion, runParams, false);
    }


    /**
     * returns all opponents.
     * Should keep player relations somewhere in the match structure
     */
    public final PlayerCollection getOpponents() {
        return game.getPlayers().filter(PlayerPredicates.isOpponentOf(this));
    }

    public void updateOpponentsForView() {
        view.updateOpponents(this);
    }

    public void updateFlashbackForView() {
        view.updateFlashbackForPlayer(this);
    }

    //get single opponent for player if only one, otherwise returns null
    //meant to be used after game ends for the sake of achievements
    public Player getSingleOpponent() {
        if (game.getRegisteredPlayers().size() == 2) {
            for (Player p : game.getRegisteredPlayers()) {
                if (p.isOpponentOf(this)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Find the smallest life total amongst this player's opponents.
     */
    public final int getOpponentsSmallestLifeTotal() {
        return Aggregates.min(getOpponents(), Accessors.FN_GET_LIFE_TOTAL);
    }

    /**
     * Find the greatest life total amongst this player's opponents.
     */
    public final int getOpponentsGreatestLifeTotal() {
        return Aggregates.max(getOpponents(), Accessors.FN_GET_LIFE_TOTAL);
    }
    
    /**
     * Get the total number of poison counters amongst this player's opponents.
     */
    public final int getOpponentsTotalPoisonCounters() {
        return Aggregates.sum(getOpponents(), Accessors.FN_GET_POISON_COUNTERS);
    }

    /**
     * returns allied players.
     * Should keep player relations somewhere in the match structure
     */
    public final PlayerCollection getAllies() {
        return getAllOtherPlayers().filter(Predicates.not(PlayerPredicates.isOpponentOf(this)));
    }

    /**
     * returns allied players.
     * Should keep player relations somewhere in the match structure
     */
    public final PlayerCollection getYourTeam() {
        return game.getPlayers().filter(Predicates.not(PlayerPredicates.isOpponentOf(this)));
    }

    /**
     * returns all other players.
     * Should keep player relations somewhere in the match structure
     */
    public final PlayerCollection getAllOtherPlayers() {
        PlayerCollection result = new PlayerCollection(game.getPlayers());
        result.remove(this);
        return result;
    }

    /**
     * returns the weakest opponent (based on life totals).
     * Should keep player relations somewhere in the match structure
     */
    public final Player getWeakestOpponent() {
        return getOpponents().min(PlayerPredicates.compareByLife());
    }

    public boolean isOpponentOf(Player other) {
        return other != this && other != null && (other.teamNumber < 0 || other.teamNumber != teamNumber);
    }

    public final boolean setLife(final int newLife, final Card source) {
        boolean change = false;
        // rule 118.5
        if (life > newLife) {
            change = (loseLife(life - newLife) > 0);
        }
        else if (newLife > life) {
            change = gainLife(newLife - life, source);
        }
        else { // life == newLife
            change = false;
        }
        return change;
    }

    public final int getStartingLife() {
        return startingLife;
    }
    public final void setStartingLife(final int startLife) {
        //Should only be called from newGame().
        startingLife = startLife;
        life = startLife;
        view.updateLife(this);
    }

    public final int getLife() {
        return life;
    }

    public final boolean gainLife(int lifeGain, final Card source) {
        return gainLife(lifeGain, source, null);
    }

    public final boolean gainLife(int lifeGain, final Card source, final SpellAbility sa) {
        
        // Run any applicable replacement effects.
        final Map<String, Object> repParams = Maps.newHashMap();
        repParams.put("Event", "GainLife");
        repParams.put("Affected", this);
        repParams.put("LifeGained", lifeGain);
        repParams.put("Source", source);

        if (!canGainLife()) {
            return false;
        }

        switch (getGame().getReplacementHandler().run(repParams)) {
        case NotReplaced:
            break;
        case Updated:
            // check if this is still the affected player
            if (this.equals(repParams.get("Affected"))) {
                lifeGain = (int) repParams.get("LifeGained");
                // negative update means life loss
                if (lifeGain < 0) {
                    this.loseLife(-lifeGain);
                    return false;
                }
            } else {
                return false;
            }
            break;
        default:       
            return false;
        }

        boolean newLifeSet = false;

        if (lifeGain > 0) {
            int oldLife = life;
            life += lifeGain;
            view.updateLife(this);
            newLifeSet = true;
            lifeGainedThisTurn += lifeGain;

            // Run triggers
            final Map<String, Object> runParams = Maps.newHashMap();
            runParams.put("Player", this);
            runParams.put("LifeAmount", lifeGain);
            runParams.put("Source", source);
            runParams.put("SourceSA", sa);
            game.getTriggerHandler().runTrigger(TriggerType.LifeGained, runParams, false);

            game.fireEvent(new GameEventPlayerLivesChanged(this, oldLife, life));
        }
        else {
            System.out.println("Player - trying to gain negative or 0 life");
        }
        return newLifeSet;
    }

    public final boolean canGainLife() {
        if (hasKeyword("You can't gain life.") || hasKeyword("Your life total can't change.")) {
            return false;
        }
        return true;
    }

    public final void refundLife(final int toAdd) {
    	int oldLife = life;
        life += toAdd;
        lifeLostThisTurn -= toAdd;
        view.updateLife(this);
        game.fireEvent(new GameEventPlayerLivesChanged(this, oldLife, life));
    }

    public final int loseLife(final int toLose) {
        int lifeLost = 0;
        if (!canLoseLife()) {
            return 0;
        }
        if (toLose > 0) {
            int oldLife = life;
            life -= toLose;
            view.updateLife(this);
            lifeLost = toLose;
            game.fireEvent(new GameEventPlayerLivesChanged(this, oldLife, life));
        }
        else if (toLose == 0) {
            // Rule 118.4
            // this is for players being able to pay 0 life nothing to do
            // no trigger for lost no life
            return 0;
        }
        else {
            System.out.println("Player - trying to lose negative life");
            return 0;
        }

        boolean firstLost = lifeLostThisTurn == 0;

        lifeLostThisTurn += toLose;

        // Run triggers
        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("Player", this);
        runParams.put("LifeAmount", toLose);
        runParams.put("FirstTime", firstLost);
        game.getTriggerHandler().runTrigger(TriggerType.LifeLost, runParams, false);

        return lifeLost;
    }

    public final boolean canLoseLife() {
        if (hasKeyword("Your life total can't change.")) {
            return false;
        }
        return true;
    }

    public final boolean canPayLife(final int lifePayment) {
        if (life < lifePayment) {
            return false;
        }
        if ((lifePayment > 0) && hasKeyword("Your life total can't change.")) {
            return false;
        }
        return true;
    }

    public final boolean payLife(final int lifePayment, final Card source) {
        if (!canPayLife(lifePayment)) {
            return false;
        }
        
        if (lifePayment <= 0) 
            return true;
        
        // rule 118.8
        if (life >= lifePayment) {
            return (loseLife(lifePayment) > 0);
        }
        return false;
    }

    public final boolean canPayEnergy(final int energyPayment) {
        int cnt = getCounters(CounterType.ENERGY);
        return cnt >= energyPayment;
    }

    public final int loseEnergy(int lostEnergy) {
        int cnt = getCounters(CounterType.ENERGY);
        if (lostEnergy > cnt) {
            return -1;
        }
        cnt -= lostEnergy;
        this.setCounters(CounterType.ENERGY, cnt, true);
        return cnt;
    }

    public final boolean payEnergy(final int energyPayment, final Card source) {
        if (energyPayment <= 0)
            return true;


        return canPayEnergy(energyPayment) && loseEnergy(energyPayment) > -1;
    }

    // This function handles damage after replacement and prevention effects are applied
    @Override
    public final int addDamageAfterPrevention(final int amount, final Card source, final boolean isCombat, CardDamageMap damageMap) {
        if (amount <= 0) {
            return 0;
        }
        //String additionalLog = "";
        source.addDealtDamageToPlayerThisTurn(getName(), amount);

        boolean infect = source.hasKeyword(Keyword.INFECT)
                || hasKeyword("All damage is dealt to you as though its source had infect.");

        if (infect) {
            addPoisonCounters(amount, source);
        }
        else {
            // Worship does not reduce the damage dealt but changes the effect
            // of the damage
            if (hasKeyword("DamageLifeThreshold:7") && life - 7 <= amount) {
                // only active if life is over 7, so no bad thing
                loseLife(Math.min(amount, life - 7));
            } else if (hasKeyword("DamageLifeThreshold:1") && life <= amount) {
                loseLife(Math.min(amount, life - 1));
            }
            else {
                // rule 118.2. Damage dealt to a player normally causes that player to lose that much life.
                loseLife(amount);
            }
        }

        //Tiny Leaders and Brawl ignore commander damage rule.
        if (source.isCommander() && isCombat && this.getGame().getRules().getGameType() != GameType.TinyLeaders
                && this.getGame().getRules().getGameType() != GameType.Brawl) {
            commanderDamage.put(source, getCommanderDamage(source) + amount);
            view.updateCommanderDamage(this);
        }

        int old = assignedDamage.containsKey(source) ? assignedDamage.get(source) : 0;
        assignedDamage.put(source, old + amount);
        source.getDamageHistory().registerDamage(this);

        if (isCombat) {
            old = assignedCombatDamage.containsKey(source) ? assignedCombatDamage.get(source) : 0;
            assignedCombatDamage.put(source, old + amount);
            for (final String type : source.getType().getCreatureTypes()) {
                source.getController().addProwlType(type);
            }
        }

        // Run triggers
        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("DamageSource", source);
        runParams.put("DamageTarget", this);
        runParams.put("DamageAmount", amount);
        runParams.put("IsCombatDamage", isCombat);
        // Defending player at the time the damage was dealt
        runParams.put("DefendingPlayer", game.getCombat() != null ? game.getCombat().getDefendingPlayerRelatedTo(source) : null);
        game.getTriggerHandler().runTrigger(TriggerType.DamageDone, runParams, false);

        game.fireEvent(new GameEventPlayerDamaged(this, source, amount, isCombat, infect));

        if (amount > 0) {
            damageMap.put(source, this, amount);
        }
        return amount;
    }

    // This should be also usable by the AI to forecast an effect (so it must not change the game state)
    @Override
    public final int staticDamagePrevention(final int damage, final Card source, final boolean isCombat, final boolean isTest) {
        if (damage <= 0) {
            return 0;
        }
        if (game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.noPrevention)) {
            return damage;
        }

        if (isCombat && game.getPhaseHandler().isPreventCombatDamageThisTurn()) {
            return 0;
        }

        if (hasProtectionFrom(source, true)) {
            return 0;
        }

        int restDamage = damage;

        // Prevent Damage static abilities
        for (final Card ca : game.getCardsIn(ZoneType.listValueOf("Battlefield,Command"))) {
            final Iterable<StaticAbility> staticAbilities = ca.getStaticAbilities();
            for (final StaticAbility stAb : staticAbilities) {
                restDamage = stAb.applyAbility("PreventDamage", source, this, restDamage, isCombat, isTest);
            }
        }

        if (restDamage > 0) {
            return restDamage;
        }
        return 0;
    }

    // This is usable by the AI to forecast an effect (so it must
    // not change the game state)
    // 2012/01/02: No longer used in calculating the finalized damage, but
    // retained for damageprediction. -Hellfish
    @Override
    public final int staticReplaceDamage(final int damage, final Card source, final boolean isCombat) {
        int restDamage = damage;

        if (hasKeyword("DamageLifeThreshold:7")) {
            restDamage = Math.min(restDamage, life - 7);
        } else if (hasKeyword("DamageLifeThreshold:1")) {
            restDamage = Math.min(restDamage, life - 1);
        }

        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Sulfuric Vapors")) {
                if (source.isSpell() && source.isRed()) {
                    restDamage += 1;
                }
            } else if (c.getName().equals("Pyromancer's Swath")) {
                if (c.getController().equals(source.getController()) && (source.isInstant() || source.isSorcery())) {
                    restDamage += 2;
                }
            } else if (c.getName().equals("Pyromancer's Gauntlet")) {
                if (c.getController().equals(source.getController()) && source.isRed()
                        && (source.isInstant() || source.isSorcery() || source.isPlaneswalker())) {
                    restDamage += 2;
                }
            } else if (c.getName().equals("Furnace of Rath") || c.getName().equals("Dictate of the Twin Gods")) {
                restDamage += restDamage;
            } else if (c.getName().equals("Gratuitous Violence")) {
                if (c.getController().equals(source.getController()) && source.isCreature()) {
                    restDamage += restDamage;
                }
            } else if (c.getName().equals("Fire Servant")) {
                if (c.getController().equals(source.getController()) && source.isRed()
                        && (source.isInstant() || source.isSorcery())) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Curse of Bloodletting")) {
                if (c.getEnchanting().equals(this)) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Gisela, Blade of Goldnight")) {
                if (!c.getController().equals(this)) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Inquisitor's Flail")) {
                if (isCombat && c.getEquipping() != null && c.getEquipping().equals(source)) {
                    restDamage *= 2;
                }
            } else if (c.getName().equals("Ghosts of the Innocent")) {
                restDamage = restDamage / 2;
            } else if (c.getName().equals("Benevolent Unicorn")) {
                if (source.isSpell()) {
                    restDamage -= 1;
                }
            } else if (c.getName().equals("Divine Presence")) {
                if (restDamage > 3) {
                    restDamage = 3;
                }
            } else if (c.getName().equals("Forethought Amulet")) {
                if (c.getController().equals(this) && (source.isInstant() || source.isSorcery())
                        && restDamage > 2) {
                    restDamage = 2;
                }
            } else if (c.getName().equals("Elderscale Wurm")) {
                if (c.getController().equals(this) && getLife() - restDamage < 7) {
                    restDamage = getLife() - 7;
                    if (restDamage < 0) {
                        restDamage = 0;
                    }
                }
            }
        }

        // TODO: improve such that this can be predicted from the replacement effect itself
        // (+ move this function out into ComputerUtilCombat?)
        for (Card c : game.getCardsIn(ZoneType.Command)) {
            if (c.getName().equals("Insult Effect")) {
                if (c.getController().equals(source.getController())) {
                    restDamage *= 2;
                }
            }
        }

        return restDamage;
    }

    protected int preventShieldEffect(final int damage) {
        if (damage <= 0) {
            return 0;
        }

        int restDamage = damage;

        boolean DEBUGShieldsWithEffects = false;
        while (!getPreventNextDamageWithEffect().isEmpty() && restDamage != 0) {
            Map<Card, Map<String, String>> shieldMap = getPreventNextDamageWithEffect();
            CardCollection preventionEffectSources = new CardCollection(shieldMap.keySet());
            Card shieldSource = preventionEffectSources.get(0);
            if (preventionEffectSources.size() > 1) {
                Map<String, Card> choiceMap = new TreeMap<String, Card>();
                List<String> choices = new ArrayList<String>();
                for (final Card key : preventionEffectSources) {
                    String effDesc = shieldMap.get(key).get("EffectString");
                    int descIndex = effDesc.indexOf("SpellDescription");
                    effDesc = effDesc.substring(descIndex + 18);
                    String shieldDescription = key.toString() + " - " + shieldMap.get(shieldSource).get("ShieldAmount")
                            + " shields - " + effDesc;
                    choices.add(shieldDescription);
                    choiceMap.put(shieldDescription, key);
                }
                shieldSource = getController().chooseProtectionShield(this, choices, choiceMap);
            }
            if (DEBUGShieldsWithEffects) {
                System.out.println("Prevention shield source: " + shieldSource);
            }

            int shieldAmount = Integer.valueOf(shieldMap.get(shieldSource).get("ShieldAmount"));
            int dmgToBePrevented = Math.min(restDamage, shieldAmount);
            if (DEBUGShieldsWithEffects) {
                System.out.println("Selected source initial shield amount: " + shieldAmount);
                System.out.println("Incoming damage: " + restDamage);
                System.out.println("Damage to be prevented: " + dmgToBePrevented);
            }

            //Set up ability
            SpellAbility shieldSA = null;
            String effectAbString = shieldMap.get(shieldSource).get("EffectString");
            effectAbString = TextUtil.fastReplace(effectAbString, "PreventedDamage", Integer.toString(dmgToBePrevented));
            effectAbString = TextUtil.fastReplace(effectAbString, "ShieldEffectTarget", shieldMap.get(shieldSource).get("ShieldEffectTarget"));
            if (DEBUGShieldsWithEffects) {
                System.out.println("Final shield ability string: " + effectAbString);
            }
            shieldSA = AbilityFactory.getAbility(effectAbString, shieldSource);
            if (shieldSA.usesTargeting()) {
                System.err.println(shieldSource + " - Targeting for prevention shield's effect should be done with initial spell");
            }

            boolean apiIsEffect = (shieldSA.getApi() == ApiType.Effect);
            CardCollectionView cardsInCommand = null;
            if (apiIsEffect) {
                cardsInCommand = getGame().getCardsIn(ZoneType.Command);
            }

            getController().playSpellAbilityNoStack(shieldSA, true);
            if (apiIsEffect) {
                CardCollection newCardsInCommand = new CardCollection(getGame().getCardsIn(ZoneType.Command));
                newCardsInCommand.removeAll(cardsInCommand);
                if (!newCardsInCommand.isEmpty()) {
                    newCardsInCommand.get(0).setSVar("PreventedDamage", "Number$" + Integer.toString(dmgToBePrevented));
                }
            }
            subtractPreventNextDamageWithEffect(shieldSource, restDamage);
            restDamage = restDamage - dmgToBePrevented;

            if (DEBUGShieldsWithEffects) {
                System.out.println("Remaining shields: "
                        + (shieldMap.containsKey(shieldSource) ? shieldMap.get(shieldSource).get("ShieldAmount") : "all shields used"));
                System.out.println("Remaining damage: " + restDamage);
            }
        }
        return restDamage;
    }

    public final void clearAssignedDamage() {
        assignedDamage.clear();
        assignedCombatDamage.clear();
    }

    public final int getAssignedDamage() {
        int num = 0;
        for (final Integer value : assignedDamage.values()) {
            num += value;
        }
        return num;
    }
    
    public final int getAssignedCombatDamage() {
        int num = 0;
        for (final Integer value : assignedCombatDamage.values()) {
            num += value;
        }
        return num;
    }

    public final Iterable<Card> getAssignedDamageSources() {
        return assignedDamage.keySet();
    }

    public final int getAssignedDamage(final String type) {
        final Map<Card, Integer> valueMap = Maps.newHashMap();
        for (final Card c : assignedDamage.keySet()) {
            if (c.getType().hasStringType(type)) {
                valueMap.put(c, assignedDamage.get(c));
            }
        }
        int num = 0;
        for (final Integer value : valueMap.values()) {
            num += value;
        }
        return num;
    }
    
    /**
     * Get the total damage assigned to this player's opponents this turn.
     */
    public final int getOpponentsAssignedDamage() {
        return Aggregates.sum(getOpponents(), Accessors.FN_GET_ASSIGNED_DAMAGE);
    }
    
    /**
     * Get the greatest amount of damage assigned to a single opponent this turn.
     */
    public final int getMaxOpponentAssignedDamage() {
        return Aggregates.max(getOpponents(), Accessors.FN_GET_ASSIGNED_DAMAGE);
    }

    public final boolean canReceiveCounters(final CounterType type) {
        if (hasKeyword("PLAYER can't have counters put on him or her.")) {
            return false;
        }
        if (type == CounterType.POISON) {
            if (hasKeyword("You can't get poison counters")) {
                return false;
            }
        }
        return true;
    }

    public final void addCounter(final CounterType counterType, final int n, final Card source, final boolean applyMultiplier) {
        addCounter(counterType, n, source, applyMultiplier, true);
    }

    @Override
    public void addCounter(CounterType counterType, int n, final Card source, boolean applyMultiplier, boolean fireEvents) {
        if (!canReceiveCounters(counterType)) {
            return;
        }

        int addAmount = n;
        if(addAmount <= 0) {
            // Can't add negative or 0 counters, bail out now
            return;
        }

        final Map<String, Object> repParams = Maps.newHashMap();
        repParams.put("Event", "AddCounter");
        repParams.put("Affected", this);
        repParams.put("Source", source);
        repParams.put("CounterType", counterType);
        repParams.put("CounterNum", addAmount);
        repParams.put("EffectOnly", applyMultiplier);

        switch (getGame().getReplacementHandler().run(repParams)) {
        case NotReplaced:
            break;
        case Updated: {
            addAmount = (int) repParams.get("CounterNum");
            break;
        }
        default:
            return;
        }

        final int oldValue = getCounters(counterType);
        final int newValue = addAmount + oldValue;
        this.setCounters(counterType, newValue, fireEvents);

        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("Player", this);
        runParams.put("Source", this);
        runParams.put("CounterType", counterType);
        for (int i = 0; i < addAmount; i++) {
            getGame().getTriggerHandler().runTrigger(TriggerType.CounterAdded, runParams, false);
        }
        if (addAmount > 0) {
            getGame().getTriggerHandler().runTrigger(TriggerType.CounterAddedOnce, runParams, false);
        }
    }

    @Override
    public void subtractCounter(CounterType counterName, int num) {
        int oldValue = getCounters(counterName);
        int newValue = Math.max(oldValue - num, 0);

        final int delta = oldValue - newValue;
        if (delta == 0) { return; }

        setCounters(counterName, newValue, true);

        /* TODO Run triggers when something cares
        int curCounters = oldValue;
        for (int i = 0; i < delta && curCounters != 0; i++) {
            final Map<String, Object> runParams = new TreeMap<>();
            runParams.put("Card", this);
            runParams.put("CounterType", counterName);
            runParams.put("NewCounterAmount", --curCounters);
            getGame().getTriggerHandler().runTrigger(TriggerType.CounterRemoved, runParams, false);
        }
        */
    }

    public final void clearCounters() {
        if (counters.isEmpty()) { return; }
        counters.clear();
        view.updateCounters(this);
        getGame().fireEvent(new GameEventPlayerCounters(this, null, 0, 0));
    }

    public void setCounters(final CounterType counterType, final Integer num, boolean fireEvents) {
        Integer old = getCounters(counterType);
        setCounters(counterType, num);
        view.updateCounters(this);
        if (fireEvents) {
            getGame().fireEvent(new GameEventPlayerCounters(this, counterType, old, num));
        }
    }

    @Override
    public void setCounters(Map<CounterType, Integer> allCounters) {
        counters = allCounters;
        view.updateCounters(this);
        getGame().fireEvent(new GameEventPlayerCounters(this, null, 0, 0));
    }

    // TODO Merge These calls into the primary counter calls
    public final int getPoisonCounters() {
        return getCounters(CounterType.POISON);
    }
    public final void setPoisonCounters(final int num, Card source) {
        int oldPoison = getCounters(CounterType.POISON);
        setCounters(CounterType.POISON, num, true);
        game.fireEvent(new GameEventPlayerPoisoned(this, source, oldPoison, num));
    }
    public final void addPoisonCounters(final int num, final Card source) {
        int oldPoison = getCounters(CounterType.POISON);
        addCounter(CounterType.POISON, num, source, false, true);

        if (oldPoison != getCounters(CounterType.POISON)) {
            game.fireEvent(new GameEventPlayerPoisoned(this, source, oldPoison, num));
        }
    }
    public final void removePoisonCounters(final int num, final Card source) {
        int oldPoison = getCounters(CounterType.POISON);
        subtractCounter(CounterType.POISON, num);

        if (oldPoison != getCounters(CounterType.POISON)) {
            game.fireEvent(new GameEventPlayerPoisoned(this, source, oldPoison, num));
        }
    }
    // ================ POISON Merged =================================

    public final void addChangedKeywords(final String[] addKeywords, final String[] removeKeywords, final Long timestamp) {
        addChangedKeywords(ImmutableList.copyOf(addKeywords), ImmutableList.copyOf(removeKeywords), timestamp);
    }

    public final void addChangedKeywords(final List<String> addKeywords, final List<String> removeKeywords, final Long timestamp) {
        // if the key already exists - merge entries
        if (changedKeywords.containsKey(timestamp)) {
            final KeywordsChange cks = changedKeywords.get(timestamp);
            
            ;
            changedKeywords.put(timestamp, cks.merge(addKeywords, removeKeywords,
                    cks.isRemoveAllKeywords(), cks.isRemoveIntrinsicKeywords()));
            updateKeywords();
            return;
        }

        changedKeywords.put(timestamp, new KeywordsChange(addKeywords, removeKeywords, false, false));
        updateKeywords();
        game.fireEvent(new GameEventPlayerStatsChanged(this));
    }

    public final KeywordsChange removeChangedKeywords(final Long timestamp) {
        KeywordsChange change = changedKeywords.remove(Long.valueOf(timestamp));
        if (change != null) {
            updateKeywords();
            game.fireEvent(new GameEventPlayerStatsChanged(this));
        }
        return change;
    }

    /**
     * Append a keyword change which adds the specified keyword.
     * @param keyword the keyword to add.
     */
    public final void addKeyword(final String keyword) {
        addChangedKeywords(ImmutableList.of(keyword), ImmutableList.<String>of(), getGame().getNextTimestamp());
    }

    /**
     * Replace all instances of added keywords.
     * @param oldKeyword the keyword to replace.
     * @param newKeyword the keyword with which to replace.
     */
    private final void replaceAllKeywordInstances(final String oldKeyword, final String newKeyword) {
        boolean keywordReplaced = false;
        for (final KeywordsChange ck : changedKeywords.values()) {
            if (ck.removeKeywordfromAdd(oldKeyword)) {
                ck.addKeyword(newKeyword);
                keywordReplaced = true;
            }
        }
        if (keywordReplaced) {
            updateKeywords();
        }
    }

    /**
     * Remove all keyword changes which grant this {@link Player} the specified
     * keyword. 
     * @param keyword the keyword to remove.
     */
    public final void removeKeyword(final String keyword) {
    	removeKeyword(keyword, true);
    }
    
    
    public final void removeKeyword(final String keyword, final boolean allInstances) {
        boolean keywordRemoved = false;

        for (final KeywordsChange ck : changedKeywords.values()) {
            if (ck.removeKeywordfromAdd(keyword)) {
                keywordRemoved = true;
                if (!allInstances) {
                	break;
                }
            }
        }

        // Remove the empty changes
        for (final Entry<Long, KeywordsChange> ck : ImmutableList.copyOf(changedKeywords.entrySet())) {
            if (ck.getValue().isEmpty() && changedKeywords.remove(ck.getKey()) != null) {
                keywordRemoved = true;
            }
        }

        if (keywordRemoved) {
            updateKeywords();
            game.fireEvent(new GameEventPlayerStatsChanged(this));
        }
    }

    @Override
    public final boolean hasKeyword(final String keyword) {
        return keywords.contains(keyword);
    }

    @Override
    public final boolean hasKeyword(final Keyword keyword) {
        return keywords.contains(keyword);
    }

    private void updateKeywords() {
        keywords.clear();

        // see if keyword changes are in effect
        for (final KeywordsChange ck : changedKeywords.values()) {
            if (ck.isRemoveAllKeywords()) {
                keywords.clear();
            }
            else if (ck.getRemoveKeywords() != null) {
                keywords.removeAll(ck.getRemoveKeywords());
            }

            if (ck.getKeywords() != null) {
                keywords.insertAll(ck.getKeywords());
            }
        }
        view.updateKeywords(this);
    }

    public final KeywordCollectionView getKeywords() {
        return keywords.getView();
    }

    public final FCollectionView<StaticAbility> getStaticAbilities() {
        FCollection<StaticAbility> result = new FCollection<StaticAbility>();
        for (DetachedCardEffect eff : staticAbilities.values()) {
            result.addAll(eff.getStaticAbilities());
        }
        return result;
    }
    
    public final StaticAbility addStaticAbility(final Card host, final String s) {
        PlayerZone com = getZone(ZoneType.Command);

        if (!staticAbilities.containsKey(host)) {
            DetachedCardEffect effect = new DetachedCardEffect(host, host.getName() + "'s Effect");
            effect.setOwner(this);
            staticAbilities.put(host, effect);
        }

        if (!com.contains(staticAbilities.get(host))) {
            com.add(staticAbilities.get(host));
            this.updateZoneForView(com);
        }

        return staticAbilities.get(host).addStaticAbility(s);
    }

    public final void clearStaticAbilities() {
        PlayerZone com = getZone(ZoneType.Command);
        for (DetachedCardEffect eff : staticAbilities.values()) {
            com.remove(eff);
            eff.setStaticAbilities(Lists.<StaticAbility>newArrayList());
        }
	    this.updateZoneForView(com);
    }

    @Override
    public final boolean canBeTargetedBy(final SpellAbility sa) {
    	if (hasKeyword("Shroud")) {
            return false;
        }
        if (hasKeyword("Hexproof")) {
            final Player a = sa.getActivatingPlayer();
            if (isOpponentOf(a)) {
                boolean cancelHexproof = false;
                for (String k : a.getKeywords()) {
                    if (k.startsWith("IgnoreHexproof")) {
                        String m[] = k.split(":");
                        if (isValid(m[1].split(","), a, sa.getHostCard(), sa)) {
                            cancelHexproof = true;
                            break;
                        }
                    }
                }
                if (!cancelHexproof) {
                    return false;
                }
            }
        }

        if (hasProtectionFrom(sa.getHostCard())) {
            return false;
        }

        if ((hasKeyword("You can't be the targets of spells or activated abilities") && (sa.isSpell() || (sa instanceof AbilityActivated)))) {
            return false;
        }
        return true;
    }

    @Override
    public boolean hasProtectionFrom(final Card source) {
        return hasProtectionFrom(source, false);
    }

    public boolean hasProtectionFrom(final Card source, final boolean damageSource) {
        for (String kw : keywords) {
            if (kw.startsWith("Protection")) {
                if (kw.startsWith("Protection:")) { // uses isValid
                    final String characteristic = kw.split(":")[1];
                    final String[] characteristics = characteristic.split(",");
                    if (source.isValid(characteristics, this, null, null)) {
                        return true;
                    }
                } else if (kw.equals("Protection from everything")) {
                    return true;
                }
                else {
                    final boolean colorlessDamage = damageSource && source.hasKeyword("Colorless Damage Source");

                    switch (kw) {
                    case "Protection from white":
                        if (source.isWhite() && !colorlessDamage) { return true; }
                        break;
                    case "Protection from blue":
                        if (source.isBlue() && !colorlessDamage) { return true; }
                        break;
                    case "Protection from black":
                        if (source.isBlack() && !colorlessDamage) { return true; }
                        break;
                    case "Protection from red":
                        if (source.isRed() && !colorlessDamage) { return true; }
                        break;
                    case "Protection from green":
                        if (source.isGreen() && !colorlessDamage) { return true; }
                        break;
                    }
                }
            }
        }
        return false;
    }

    public final boolean canDraw() {
        if (hasKeyword("You can't draw cards.")) {
            return false;
        }
        if (hasKeyword("You can't draw more than one card each turn.")) {
            return numDrawnThisTurn < 1;
        }
        return true;
    }

    public final CardCollectionView drawCard() {
        return drawCards(1);
    }

    public void scry(final int numScry, boolean shouldTrigger) {
        final CardCollection topN = new CardCollection();
        final PlayerZone library = getZone(ZoneType.Library);
        final int actualNumScry = Math.min(numScry, library.size());

        if (actualNumScry == 0) { return; }

        for (int i = 0; i < actualNumScry; i++) {
            topN.add(library.get(i));
        }

        final ImmutablePair<CardCollection, CardCollection> lists = getController().arrangeForScry(topN);
        final CardCollection toTop = lists.getLeft();
        final CardCollection toBottom = lists.getRight();

        int numToBottom = 0;
        int numToTop = 0;

        if (toBottom != null) {
            for(Card c : toBottom) {
                getGame().getAction().moveToBottomOfLibrary(c, null, null);
                numToBottom++;
            }
        }

        if (toTop != null) {
            Collections.reverse(toTop); // the last card in list will become topmost in library, have to revert thus.
            for(Card c : toTop) {
                getGame().getAction().moveToLibrary(c, null, null);
                numToTop++;
            }
        }

        getGame().fireEvent(new GameEventScry(this, numToTop, numToBottom));

        if (shouldTrigger) {
            final Map<String, Object> runParams = Maps.newHashMap();
            runParams.put("Player", this);
            getGame().getTriggerHandler().runTrigger(TriggerType.Scry, runParams, false);
        }
    }

    public boolean canMulligan() {
        return !getZone(ZoneType.Hand).isEmpty();
    }

    public final CardCollectionView drawCards(final int n) {
        final CardCollection drawn = new CardCollection();
        final CardCollection toReveal = new CardCollection();

        // Replacement effects
        final Map<String, Object> repRunParams = Maps.newHashMap();
        repRunParams.put("Event", "DrawCards");
        repRunParams.put("Affected", this);
        repRunParams.put("Number", n);

        if (game.getReplacementHandler().run(repRunParams) != ReplacementResult.NotReplaced) {
            return drawn;
        }

        // always allow drawing cards before the game actually starts (e.g. Maralen of the Mornsong Avatar)
        final boolean gameStarted = game.getAge().ordinal() > GameStage.Mulligan.ordinal();

        for (int i = 0; i < n; i++) {
            if (gameStarted && !canDraw()) {
                return drawn;
            }
            drawn.addAll(doDraw(toReveal));
        }
        if (toReveal.size() > 1) {
            // reveal multiple drawn cards when playing with the top of the library revealed
            game.getAction().reveal(toReveal, this, true, "Revealing cards drawn from ");
        }
        return drawn;
    }

    /**
     * @return a CardCollectionView of cards actually drawn
     */
    private CardCollectionView doDraw(CardCollection revealed) {
        final CardCollection drawn = new CardCollection();
        final PlayerZone library = getZone(ZoneType.Library);

        // Replacement effects
        final Map<String, Object> repRunParams = Maps.newHashMap();
        repRunParams.put("Event", "Draw");
        repRunParams.put("Affected", this);

        if (game.getReplacementHandler().run(repRunParams) != ReplacementResult.NotReplaced) {
            return drawn;
        }

        if (!library.isEmpty()) {
            Card c = library.get(0);
            boolean topCardRevealed = false;

            for (Player p : this.getAllOtherPlayers()) {
                if (c.mayPlayerLook(p)) {
                    topCardRevealed = true;
                    break;
                }
            }

            c = game.getAction().moveToHand(c, null, null);
            drawn.add(c);

            if (topCardRevealed) {
                revealed.add(c);
            } 

            if (numDrawnThisTurn == 0) {
                boolean reveal = false;
                final CardCollectionView cards = getCardsIn(ZoneType.Battlefield);
                for (final Card card : cards) {
                    if (card.hasKeyword("Reveal the first card you draw each turn")
                            || (card.hasKeyword("Reveal the first card you draw on each of your turns") && game.getPhaseHandler().isPlayerTurn(this))) {
                        reveal = true;
                        break;
                    }
                }
                if (reveal) {
                    game.getAction().reveal(drawn, this, true, "Revealing the first card drawn from ");
                    if (revealed.contains(c)) {
                        revealed.remove(c);
                    }
                }
            } 

            setLastDrawnCard(c);
            c.setDrawnThisTurn(true);
            numDrawnThisTurn++;
            if (game.getPhaseHandler().is(PhaseType.DRAW)) {
                numDrawnThisDrawStep++;
            }
            view.updateNumDrawnThisTurn(this);

            // Run triggers
            final Map<String, Object> runParams = Maps.newHashMap();
            runParams.put("Card", c);
            runParams.put("Number", numDrawnThisTurn);
            runParams.put("Player", this);
            game.getTriggerHandler().runTrigger(TriggerType.Drawn, runParams, false);
        }
        else { // Lose by milling is always on. Give AI many cards it cannot play if you want it not to undertake actions
            triedToDrawFromEmptyLibrary = true;
        }
        return drawn;
    }

    /**
     * Returns PlayerZone corresponding to the given zone of game.
     */
    public final PlayerZone getZone(final ZoneType zone) {
        return zones.get(zone);
    }
    public void updateZoneForView(PlayerZone zone) {
        view.updateZone(zone);
    }

    public final CardCollectionView getCardsIn(final ZoneType zoneType) {
        return getCardsIn(zoneType, true);
    }

    /**
     * gets a list of all cards in the requested zone. This function makes a CardCollectionView from Card[].
     */
    public final CardCollectionView getCardsIn(final ZoneType zoneType, boolean filterOutPhasedOut) {
        if (zoneType == ZoneType.Stack) {
            CardCollection cards = new CardCollection();
            for (Card c : game.getStackZone().getCards()) {
                if (c.getOwner().equals(this)) {
                    cards.add(c);
                }
            }
            return cards;
        }
        else if (zoneType == ZoneType.Flashback) {
            return getCardsActivableInExternalZones(true);
        }

        PlayerZone zone = getZone(zoneType);
        return zone == null ? CardCollection.EMPTY : zone.getCards(filterOutPhasedOut);
    }

    public final CardCollectionView getCardsIncludePhasingIn(final ZoneType zone) {
        return getCardsIn(zone, false);
    }

    /**
     * gets a list of first N cards in the requested zone. This function makes a CardCollectionView from Card[].
     */
    public final CardCollectionView getCardsIn(final ZoneType zone, final int n) {
        return new CardCollection(Iterables.limit(getCardsIn(zone), n));
    }

    /**
     * gets a list of all cards in a given player's requested zones.
     */
    public final CardCollectionView getCardsIn(final Iterable<ZoneType> zones) {
        final CardCollection result = new CardCollection();
        for (final ZoneType z : zones) {
            result.addAll(getCardsIn(z));
        }
        return result;
    }
    public final CardCollectionView getCardsIn(final ZoneType[] zones) {
        final CardCollection result = new CardCollection();
        for (final ZoneType z : zones) {
            result.addAll(getCardsIn(z));
        }
        return result;
    }

    /**
     * gets a list of all cards with requested cardName in a given player's
     * requested zone. This function makes a CardCollectionView from Card[].
     */
    public final CardCollectionView getCardsIn(final ZoneType zone, final String cardName) {
        return CardLists.filter(getCardsIn(zone), CardPredicates.nameEquals(cardName));
    }

    public CardCollectionView getCardsActivableInExternalZones(boolean includeCommandZone) {
        final CardCollection cl = new CardCollection();

        cl.addAll(getZone(ZoneType.Graveyard).getCardsPlayerCanActivate(this));
        cl.addAll(getZone(ZoneType.Graveyard).getCardsRetrace(this, getZone(ZoneType.Hand).getCards()));
        cl.addAll(getZone(ZoneType.Exile).getCardsPlayerCanActivate(this));
        cl.addAll(getZone(ZoneType.Exile).getCardsSuspended(this));
        cl.addAll(getZone(ZoneType.Library).getCardsPlayerCanActivate(this));
        if (includeCommandZone) {
            cl.addAll(getZone(ZoneType.Command).getCardsPlayerCanActivate(this));
        }

        //External activatables from all opponents
        for (final Player opponent : getOpponents()) {
            cl.addAll(opponent.getZone(ZoneType.Exile).getCardsPlayerCanActivate(this));
            cl.addAll(opponent.getZone(ZoneType.Graveyard).getCardsPlayerCanActivate(this));
            cl.addAll(opponent.getZone(ZoneType.Library).getCardsPlayerCanActivate(this));
            if (opponent.hasKeyword("Play with your hand revealed.")) {
                cl.addAll(opponent.getZone(ZoneType.Hand).getCardsPlayerCanActivate(this));
            }
        }
        cl.addAll(getGame().getCardsPlayerCanActivateInStack());
        return cl;
    }

    public final CardCollectionView getAllCards() {
        return CardCollection.combine(getCardsIn(Player.ALL_ZONES), getCardsIn(ZoneType.Stack), inboundTokens);
    }

    public final void resetNumDrawnThisTurn() {
        numDrawnThisTurn = 0;
        numDrawnThisDrawStep = 0;
        view.updateNumDrawnThisTurn(this);
    }

    public final int getNumDrawnThisTurn() {
        return numDrawnThisTurn;
    }

    public final int numDrawnThisDrawStep() {
        return numDrawnThisDrawStep;
    }

    public final Card discard(final Card c, final SpellAbility sa) {
        // TODO: This line should be moved inside CostPayment somehow
        /*if (sa != null) {
            sa.addCostToHashList(c, "Discarded");
        }*/
        final Card source = sa != null ? sa.getHostCard() : null;

        boolean discardToTopOfLibrary = null != sa && sa.hasParam("DiscardToTopOfLibrary");
        boolean discardMadness = sa != null && sa.hasParam("Madness");

        // DiscardToTopOfLibrary and Madness are replacement discards,
        // that should not trigger other Replacement again
        if (!discardToTopOfLibrary && !discardMadness) {
            // Replacement effects
            final Map<String, Object> repRunParams = Maps.newHashMap();
            repRunParams.put("Event", "Discard");
            repRunParams.put("Card", c);
            repRunParams.put("Source", source);
            repRunParams.put("Affected", this);

            if (game.getReplacementHandler().run(repRunParams) != ReplacementResult.NotReplaced) {
                return null;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(this).append(" discards ").append(c);
        final Card newCard;
        if (discardToTopOfLibrary) {
            newCard = game.getAction().moveToLibrary(c, 0, sa, null);
            sb.append(" to the library");
            // Play the Discard sound
        }
        else if (discardMadness) {
            newCard = game.getAction().exile(c, sa, null);
            sb.append(" with Madness");
        }
        else {
            newCard = game.getAction().moveToGraveyard(c, sa, null);
            // Play the Discard sound
        }
        sb.append(".");
        numDiscardedThisTurn++;
        // Run triggers
        Card cause = null;
        if (sa != null) {
            cause = sa.getHostCard();
            // for Replacement of the discard Cause
            if (sa.hasParam("Cause")) {
                final CardCollection col = AbilityUtils.getDefinedCards(cause, sa.getParam("Cause"), sa);
                if (!col.isEmpty()) {
                    cause = col.getFirst();
                }
            }
        }
        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("Player", this);
        runParams.put("Card", c);
        runParams.put("Cause", cause);
        runParams.put("IsMadness", Boolean.valueOf(discardMadness));
        game.getTriggerHandler().runTrigger(TriggerType.Discarded, runParams, false);
        game.getGameLog().add(GameLogEntryType.DISCARD, sb.toString());
        return newCard;
    }

    public final int getNumDiscardedThisTurn() {
        return numDiscardedThisTurn;
    }
    public final void resetNumDiscardedThisTurn() {
        numDiscardedThisTurn = 0;
    }

    public int getNumCardsInHandStartedThisTurnWith() {
        return numCardsInHandStartedThisTurnWith;
    }
    public void setNumCardsInHandStartedThisTurnWith(int num) {
        numCardsInHandStartedThisTurnWith = num;
    }

    public final CardCollectionView mill(final int n) {
        return mill(n, ZoneType.Graveyard, false);
    }
    public final CardCollectionView mill(final int n, final ZoneType zone, 
            final boolean bottom) {
        final CardCollectionView lib = getCardsIn(ZoneType.Library);
        final CardCollection milled = new CardCollection();

        final int max = Math.min(n, lib.size());

        final ZoneType destination = getZone(zone).getZoneType();

        for (int i = 0; i < max; i++) {
            if (bottom) {
                milled.add(lib.get(lib.size() - i - 1));
            }
            else {
                milled.add(lib.get(i));
            }
        }

        CardCollectionView milledView = milled;
        if (destination == ZoneType.Graveyard && milled.size() > 1) {
            milledView = GameActionUtil.orderCardsByTheirOwners(game, milled, ZoneType.Graveyard);
        }

        for (Card m : milledView) {
            game.getAction().moveTo(destination, m, null, null);
        }

        return milled;
    }

    public final CardCollection getTopXCardsFromLibrary(int amount) {
        final CardCollection topCards = new CardCollection();
        final PlayerZone lib = this.getZone(ZoneType.Library);
        int maxCards = lib.size();
        // If library is smaller than N, only get that many cards
        maxCards = Math.min(maxCards, amount);

        // show top n cards:
        for (int j = 0; j < maxCards; j++) {
            topCards.add(lib.get(j));
        }

        return topCards;
    }

    private final void shuffleCards(CardCollection list) {
        // overdone but wanted to make sure it was really random
        final Random random = MyRandom.getRandom();
        Collections.shuffle(list, random);
        Collections.shuffle(list, random);
        Collections.shuffle(list, random);
        Collections.shuffle(list, random);
        Collections.shuffle(list, random);
        Collections.shuffle(list, random);

        int s = list.size();
        for (int i = 0; i < s; i++) {
            list.add(random.nextInt(s - 1), list.remove(random.nextInt(s)));
        }

        Collections.shuffle(list, random);
        Collections.shuffle(list, random);
        Collections.shuffle(list, random);
        Collections.shuffle(list, random);
        Collections.shuffle(list, random);
        Collections.shuffle(list, random);
    }

    public final void MTGAShuffle(final SpellAbility sa) {
        if (getCardsIn(ZoneType.Library).size() < getMaxHandSize() * 2 || getMaxHandSize() == 0) {
            return;
        }

        int lands = 0;
        for(Card c : getCardsIn(ZoneType.Library)) {
            if(c.isLand()) {
                lands++;
            }
        }
        float deckRadio = (float)lands / getCardsIn(ZoneType.Library).size();

        final CardCollection[] lists = new CardCollection[3];
        float[] ratios = new float[3];

        for(int i = 0; i < lists.length; i++) {
        	lists[i] = new CardCollection(getCardsIn(ZoneType.Library));
        	shuffleCards(lists[i]);

            lands = 0;
            for(int j = 0; j < getMaxHandSize(); j++) {
                if(lists[i].get(j).isLand()) {
                	lands++;
                }
            }
            float handRatios = (float)lands / getMaxHandSize();
            System.err.print(String.format("[%d] %.02f(%d)", i + 1, handRatios * 100.0f, lands));

            lands = 0;
            for(int j = 0; j < getMaxHandSize(); j++) {
                if(lists[i].get(j + getMaxHandSize()).isLand()) {
                	lands++;
                }
            }
            float nextHandRatios = (float)lands / getMaxHandSize();

            ratios[i] = (handRatios * 3.0f + nextHandRatios) / 4.0f;
            System.err.print(String.format("/%.02f(%d)=%.02f ", nextHandRatios * 100.0f, lands, ratios[i] * 100.0f));
        }

        int index = 0;
        float min = 1.0f;
        for(int i = 0; i < lists.length; i++) {
        	if(Math.abs(ratios[i] - deckRadio) < min) {
        		min = Math.abs(ratios[i] - deckRadio);
        		index = i;
        	}
        }

        System.err.println(String.format("[Deck] %.02f -> Choose [%d]", deckRadio * 100.0f, index + 1));
        getZone(ZoneType.Library).setCards(lists[index]);

        // Run triggers
        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("Player", this);
        runParams.put("Source", sa);
        game.getTriggerHandler().runTrigger(TriggerType.Shuffled, runParams, false);

        // Play the shuffle sound
        game.fireEvent(new GameEventShuffle(this));
    }

    public final void shuffle(final SpellAbility sa) {
        final CardCollection list = new CardCollection(getCardsIn(ZoneType.Library));

        if (list.size() <= 1) {
            return;
        }

        shuffleCards(list);

        getZone(ZoneType.Library).setCards(list);

        // Run triggers
        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("Player", this);
        runParams.put("Source", sa);
        game.getTriggerHandler().runTrigger(TriggerType.Shuffled, runParams, false);

        // Play the shuffle sound
        game.fireEvent(new GameEventShuffle(this));
    }

    public final boolean playLand(final Card land, final boolean ignoreZoneAndTiming) {
        // Dakkon Blackblade Avatar will use a similar effect
        if (canPlayLand(land, ignoreZoneAndTiming)) {
            this.playLandNoCheck(land);
            return true;
        }

        game.getStack().unfreezeStack();
        return false;
    }

    public final void playLandNoCheck(final Card land) {
        land.setController(this, 0);
        if (land.isFaceDown()) {
            land.turnFaceUp();
        }
        game.getAction().moveTo(getZone(ZoneType.Battlefield), land, null, new HashMap<String, Object>());

        // play a sound
        game.fireEvent(new GameEventLandPlayed(this, land));

        // Run triggers
        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("Card", land);
        game.getTriggerHandler().runTrigger(TriggerType.LandPlayed, runParams, false);
        game.getStack().unfreezeStack();
        addLandPlayedThisTurn();
    }

    public final boolean canPlayLand(final Card land) {
        return canPlayLand(land, false);
    }
    public final boolean canPlayLand(final Card land, final boolean ignoreZoneAndTiming) {
        if (!ignoreZoneAndTiming && !canCastSorcery()) {
            return false;
        }

        if (!game.getPhaseHandler().isPlayerTurn(this)) {
            return false;
        }

        // CantBeCast static abilities
        for (final Card ca : game.getCardsIn(ZoneType.listValueOf("Battlefield,Command"))) {
            final Iterable<StaticAbility> staticAbilities = ca.getStaticAbilities();
            for (final StaticAbility stAb : staticAbilities) {
                if (stAb.applyAbility("CantPlayLand", land, this)) {
                    return false;
                }
            }
        }

        if (land != null && !ignoreZoneAndTiming) {
            final boolean mayPlay = !land.mayPlay(this).isEmpty();
            if (land.getOwner() != this && !mayPlay) {
                return false;
            }

            final Zone zone = game.getZoneOf(land);
            if (zone != null && (zone.is(ZoneType.Battlefield) || (!zone.is(ZoneType.Hand) && !mayPlay))) {
                return false;
            }
        }

        // **** Check for land play limit per turn ****
        // Dev Mode
        if (getController().canPlayUnlimited() || hasKeyword("You may play any number of additional lands on each of your turns.")) {
            return true;
        }

        // check for adjusted max lands play per turn
        int adjMax = 1;
        for (String keyword : keywords) {
            if (keyword.startsWith("AdjustLandPlays")) {
                final String[] k = keyword.split(":");
                adjMax += Integer.valueOf(k[1]);
            }
        }
        if (landsPlayedThisTurn < adjMax) {
            return true;
        }
        return false;
    }

    public final ManaPool getManaPool() {
        return manaPool;
    }
    public void updateManaForView() {
        view.updateMana(this);
    }

    public final int getNumPowerSurgeLands() {
        return numPowerSurgeLands;
    }
    public final int setNumPowerSurgeLands(final int n) {
        numPowerSurgeLands = n;
        return numPowerSurgeLands;
    }

    public final Card getLastDrawnCard() {
        return lastDrawnCard;
    }
    private final Card setLastDrawnCard(final Card c) {
        lastDrawnCard = c;
        return lastDrawnCard;
    }

    public final String getNamedCard() {
        return namedCard;
    }
    public final void setNamedCard(final String s) {
        namedCard = s;
    }

    public final int getTurn() {
        return stats.getTurnsPlayed();
    }

    public final void incrementTurn() {
        stats.nextTurn();
    }

    public boolean hasTappedLandForManaThisTurn() {
		return tappedLandForManaThisTurn;
	}

	public void setTappedLandForManaThisTurn(boolean tappedLandForManaThisTurn) {
		this.tappedLandForManaThisTurn = tappedLandForManaThisTurn;
	}

	public final boolean getActivateLoyaltyAbilityThisTurn() {
        return activateLoyaltyAbilityThisTurn;
    }
    public final void setActivateLoyaltyAbilityThisTurn(final boolean b) {
        activateLoyaltyAbilityThisTurn = b;
    }

    public final boolean getAttackedWithCreatureThisTurn() {
        return attackedWithCreatureThisTurn;
    }
    public final void setAttackedWithCreatureThisTurn(final boolean b) {
        attackedWithCreatureThisTurn = b;
    }

    public final int getAttackersDeclaredThisTurn() {
        return attackersDeclaredThisTurn;
    }
    public final void incrementAttackersDeclaredThisTurn() {
        attackersDeclaredThisTurn++;
    }
    public final void resetAttackersDeclaredThisTurn() {
        attackersDeclaredThisTurn = 0;
    }

    public final void altWinBySpellEffect(final String sourceName) {
        if (cantWin()) {
            System.out.println("Tried to win, but currently can't.");
            return;
        }
        setOutcome(PlayerOutcome.altWin(sourceName));
    }

    public final boolean loseConditionMet(final GameLossReason state, final String spellName) {
        if (state != GameLossReason.OpponentWon) {
            if (cantLose()) {
                System.out.println("Tried to lose, but currently can't.");
                return false;
            }

            // Replacement effects
            final Map<String, Object> runParams = Maps.newHashMap();
            runParams.put("Affected", this);
            runParams.put("Event", "GameLoss");

            if (game.getReplacementHandler().run(runParams) != ReplacementResult.NotReplaced) {
                return false;
            }
        }
        setOutcome(PlayerOutcome.loss(state, spellName));
        return true;
    }

    public final void concede() { // No cantLose checks - just lose
        setOutcome(PlayerOutcome.concede());
    }

    public final void intentionalDraw() {
        setOutcome(PlayerOutcome.draw());
    }

    public final boolean cantLose() {
        if (getOutcome() != null && getOutcome().lossState == GameLossReason.Conceded) {
            return false;
        }
        return hasKeyword("You can't lose the game.");
    }

    public final boolean cantLoseForZeroOrLessLife() {
        return (hasKeyword("You don't lose the game for having 0 or less life."));
    }

    public final boolean cantWin() {
        boolean isAnyOppLoseProof = false;
        for (Player p : game.getPlayers()) {
            if (p == this || p.getOutcome() != null) {

                continue; // except self and already dead
            }
            isAnyOppLoseProof |= p.hasKeyword("You can't lose the game.");
        }
        return hasKeyword("You can't win the game.") || isAnyOppLoseProof;
    }

    public final boolean checkLoseCondition() {
        // Just in case player already lost
        if (getOutcome() != null) {
            return getOutcome().lossState != null;
        }

        // Rule 704.5a -  If a player has 0 or less life, he or she loses the game.
        final boolean hasNoLife = getLife() <= 0;
        if (hasNoLife && !cantLoseForZeroOrLessLife()) {
            return loseConditionMet(GameLossReason.LifeReachedZero, null);
        }

        // Rule 704.5b - If a player attempted to draw a card from a library with no cards in it
        //               since the last time state-based actions were checked, he or she loses the game.
        if (triedToDrawFromEmptyLibrary) {
            triedToDrawFromEmptyLibrary = false; // one-shot check
            return loseConditionMet(GameLossReason.Milled, null);
        }

        // Rule 704.5c - If a player has ten or more poison counters, he or she loses the game.
        if (getCounters(CounterType.POISON) >= 10) {
            return loseConditionMet(GameLossReason.Poisoned, null);
        }

        if (game.getRules().hasAppliedVariant(GameType.Commander)) {
            for (Entry<Card, Integer> entry : getCommanderDamage()) {
                if (entry.getValue() >= 21) {
                    return loseConditionMet(GameLossReason.CommanderDamage, null);
                }
            }
        }
        return false;
    }

    public final boolean hasLost() {
        return getOutcome() != null && getOutcome().lossState != null;
    }

    public final boolean hasWon() {
        if (cantWin()) {
            return false;
        }
        // in multiplayer game one player's win is replaced by all other's lose (rule 103.4h)
        // so if someone cannot lose, the game appears to continue
        return getOutcome() != null && getOutcome().lossState == null;
    }

    public final boolean hasMetalcraft() {
        return CardLists.count(getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.ARTIFACTS) >= 3;
    }

    public final boolean hasDesert() {
        return CardLists.count(
                getCardsIn(Arrays.asList(ZoneType.Battlefield, ZoneType.Graveyard)),
                CardPredicates.isType("Desert")) > 0;
    }

    public final boolean hasThreshold() {
        return getZone(ZoneType.Graveyard).size() >= 7;
    }

    public final boolean hasHellbent() {
        return getZone(ZoneType.Hand).isEmpty();
    }

    public final boolean hasRevolt() {
        return revolt;
    }

    public final void setRevolt(final boolean val) {
        revolt = val;
    }

    public final boolean hasDelirium() {
        return CardFactoryUtil.getCardTypesFromList(getCardsIn(ZoneType.Graveyard)) >= 4;
    }

    public final boolean hasLandfall() {
        final CardCollectionView list = getZone(ZoneType.Battlefield).getCardsAddedThisTurn(null);
        return Iterables.any(list, CardPredicates.Presets.LANDS);
    }

    public final boolean hasBloodthirst() {
        for (Player p : game.getRegisteredPlayers()) {
            if (p.isOpponentOf(this) && p.getAssignedDamage() > 0) {
                return true;
            }
        }
        return false;
    }

    public boolean hasFerocious() {
        return !CardLists.filterPower(getCreaturesInPlay(), 4).isEmpty();
    }

    public final int getBloodthirstAmount() {
        return Aggregates.sum(Iterables.filter(
                game.getRegisteredPlayers(), PlayerPredicates.isOpponentOf(this)), Accessors.FN_GET_ASSIGNED_DAMAGE);
    }

    public final boolean hasSurge() {
        PlayerCollection list = getAllies();
        list.add(this);
        return !CardLists.filterControlledBy(game.getStack().getSpellsCastThisTurn(), list).isEmpty();
    }

    public final boolean hasProwl(final String type) {
        if (prowl.contains("AllCreatureTypes")) {
            return true;
        }
        return prowl.contains(type);
    }
    public final void addProwlType(final String type) {
        prowl.add(type);
    }
    public final void resetProwl() {
        prowl.clear();
    }

    public final void setLibrarySearched(final int l) {
        numLibrarySearchedOwn = l;
    }

    public final int getLibrarySearched() {
        return numLibrarySearchedOwn;
    }
    public final void incLibrarySearched() {
        numLibrarySearchedOwn++;
    }

    public final void setNumManaConversion(final int l) {
        numManaConversion = l;
    }
    public final boolean hasManaConversion() {
        return numManaConversion < keywords.getAmount("You may spend mana as though"
                + " it were mana of any color to cast a spell this turn.");
    }
    public final void incNumManaConversion() {
        numManaConversion++;
    }
    public final void decNumManaConversion() {
        numManaConversion--;
    }

    @Override
    public final boolean isValid(final String restriction, final Player sourceController, final Card source, SpellAbility spellAbility) {
        // if the current player has already lost the game, he can't be a valid target
        if (hasLost()) {
            return false;
        }

        final String[] incR = restriction.split("\\.", 2);

        if (incR[0].equals("Opponent")) {
            if (equals(sourceController) || !isOpponentOf(sourceController)) {
                return false;
            }
        } else if (incR[0].equals("You")) {
            if (!equals(sourceController)) {
                return false;
            }
        } else if (incR[0].equals("EnchantedController")) {
            final GameEntity enchanted = source.getEnchanting();
            if ((enchanted == null) || !(enchanted instanceof Card)) {
                return false;
            }
            final Card enchantedCard = (Card) enchanted;
            if (!equals(enchantedCard.getController())) {
                return false;
            }
        } else {
            if (!incR[0].equals("Player")) {
                return false;
            }
        }

        if (incR.length > 1) {
            final String excR = incR[1];
            final String[] exR = excR.split("\\+"); // Exclusive Restrictions
            // are ...
            for (int j = 0; j < exR.length; j++) {
                if (!hasProperty(exR[j], sourceController, source, spellAbility)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public final boolean hasProperty(final String property, final Player sourceController, final Card source, SpellAbility spellAbility) {
        return PlayerProperty.playerHasProperty(this, property, sourceController, source, spellAbility);
    }

    public final int getMaxHandSize() {
        return maxHandSize;
    }
    public final void setMaxHandSize(int size) {
        if (maxHandSize == size) { return; }
        maxHandSize = size;
        view.updateMaxHandSize(this);
    }

    public boolean isUnlimitedHandSize() {
        return unlimitedHandSize;
    }
    public void setUnlimitedHandSize(boolean unlimited) {
        if (unlimitedHandSize == unlimited) { return; }
        unlimitedHandSize = unlimited;
        view.updateUnlimitedHandSize(this);
    }

    public final int getLandsPlayedThisTurn() {
        return landsPlayedThisTurn;
    }
    public final int getLandsPlayedLastTurn() {
        return landsPlayedLastTurn;
    }
    public final void addLandPlayedThisTurn() {
        landsPlayedThisTurn++;
        achievementTracker.landsPlayed++;
        view.updateLandsPlayedThisTurn(this);
    }
    public final void resetLandsPlayedThisTurn() {
        landsPlayedThisTurn = 0;
        view.updateLandsPlayedThisTurn(this);
    }
    public final void setLandsPlayedThisTurn(int num) {
        // This method should only be used directly when setting up the game state.
        landsPlayedThisTurn = num;
    }
    public final void setLandsPlayedLastTurn(int num) {
        landsPlayedLastTurn = num;
    }

    public final int getInvestigateNumThisTurn() {
        return investigatedThisTurn;
    }
    public final void addInvestigatedThisTurn() {
        investigatedThisTurn++;
        Map<String,Object> runParams = Maps.newHashMap();
        runParams.put("Player", this);
        runParams.put("Num", investigatedThisTurn);
        game.getTriggerHandler().runTrigger(TriggerType.Investigated, runParams,false);
    }
    public final void resetInvestigatedThisTurn() {
        investigatedThisTurn = 0;
    }

    public final CardCollectionView getSacrificedThisTurn() {
        return sacrificedThisTurn;
    }

    public final void addSacrificedThisTurn(final Card c, final SpellAbility source) {
        // Play the Sacrifice sound
        game.fireEvent(new GameEventCardSacrificed());

        final Card cpy = CardUtil.getLKICopy(c);
        sacrificedThisTurn.add(cpy);

        // Run triggers
        final Map<String, Object> runParams = Maps.newHashMap();
        // use a copy that preserves last known information about the card (e.g. for Savra, Queen of the Golgari + Painter's Servant)
        runParams.put("Card", cpy);
        runParams.put("Player", this);
        runParams.put("Cause", source);
        runParams.put("CostStack", game.costPaymentStack);
        runParams.put("IndividualCostPaymentInstance", game.costPaymentStack.peek());
        game.getTriggerHandler().runTrigger(TriggerType.Sacrificed, runParams, false);
    }

    public final void resetSacrificedThisTurn() {
        sacrificedThisTurn.clear();
    }
    
    public final void addCounterToPermThisTurn(final CounterType type, final int x) {
        countersAddedtoPermThisTurn.put(type, getCounterToPermThisTurn(type) + x);
    }

    public final Integer getCounterToPermThisTurn(final CounterType type) {
        if (countersAddedtoPermThisTurn.containsKey(type))
            return countersAddedtoPermThisTurn.get(type);
        return 0;
    }

    public final void resetCounterToPermThisTurn() {
        countersAddedtoPermThisTurn.clear();
    }

    public final int getSpellsCastThisTurn() {
        return spellsCastThisTurn;
    }
    public final int getSpellsCastLastTurn() {
        return spellsCastLastTurn;
    }
    public final void addSpellCastThisTurn() {
        spellsCastThisTurn++;
        achievementTracker.spellsCast++;
        if (spellsCastThisTurn > achievementTracker.maxStormCount) {
            achievementTracker.maxStormCount = spellsCastThisTurn;
        }
        view.updateSpellsCastThisTurn(this);
    }
    public final void resetSpellsCastThisTurn() {
        spellsCastThisTurn = 0;
        view.updateSpellsCastThisTurn(this);
    }
    public final void setSpellsCastLastTurn(int num) {
        spellsCastLastTurn = num;
    }

    public final int getLifeGainedThisTurn() {
        return lifeGainedThisTurn;
    }
    public final void setLifeGainedThisTurn(final int n) {
        lifeGainedThisTurn = n;
    }

    public final int getLifeLostThisTurn() {
        return lifeLostThisTurn;
    }
    public final void setLifeLostThisTurn(final int n) {
        lifeLostThisTurn = n;
    }

    public final int getLifeLostLastTurn() {
        return lifeLostLastTurn;
    }
    public final void setLifeLostLastTurn(final int n) {
        lifeLostLastTurn = n;
    }

    /**
     * get the Player object or Card (Planeswalker) object that this Player must
     * attack this combat.
     * 
     * @return the Player or Card (Planeswalker)
     * @since 1.1.01
     */
    public final GameEntity getMustAttackEntity() {
        return mustAttackEntity;
    }
    public final void setMustAttackEntity(final GameEntity o) {
        mustAttackEntity = o;
    }
    public final GameEntity getMustAttackEntityThisTurn() { return mustAttackEntityThisTurn; }
    public final void setMustAttackEntityThisTurn(GameEntity entThisTurn) { mustAttackEntityThisTurn = entThisTurn; }

    @Override
    public int compareTo(Player o) {
        if (o == null) {
            return 1;
        }
        return getName().compareTo(o.getName());
    }

    public static class Accessors {
        public static Function<Player, String> FN_GET_NAME = new Function<Player, String>() {
            @Override
            public String apply(Player input) {
                return input.getName();
            }
        };
        public static Function<Player, Integer> FN_GET_LIFE_TOTAL = new Function<Player, Integer>() {
            @Override
            public Integer apply(Player input) {
                return input.getLife();
            }
        };
        public static Function<Player, Integer> FN_GET_POISON_COUNTERS = new Function<Player, Integer>() {
            @Override
            public Integer apply(Player input) {
                return input.getPoisonCounters();
            }
        };
        public static final Function<Player, Integer> FN_GET_ASSIGNED_DAMAGE = new Function<Player, Integer>() {
            @Override
            public Integer apply(Player input) {
                return input.getAssignedDamage();
            }
        };
    }

    public final LobbyPlayer getLobbyPlayer() {
        return getController().getLobbyPlayer();
    }

    public final LobbyPlayer getOriginalLobbyPlayer() {
        return controllerCreator.getLobbyPlayer();
    }

    public final RegisteredPlayer getRegisteredPlayer() {
        return game.getMatch().getPlayers().get(game.getRegisteredPlayers().indexOf(this));
    }

    public final boolean isMindSlaved() {
        return mindSlaveMaster != null;
    }

    public final Player getMindSlaveMaster() {
        return mindSlaveMaster;
    }

    public final void setMindSlaveMaster(final Player mindSlaveMaster0) {
        if (mindSlaveMaster == mindSlaveMaster0) {
            return;
        }
        mindSlaveMaster = mindSlaveMaster0;
        view.updateMindSlaveMaster(this);

        if (mindSlaveMaster != null) {
            final LobbyPlayer oldLobbyPlayer = getLobbyPlayer();
            final PlayerController oldController = getController();
            final IGameEntitiesFactory master = (IGameEntitiesFactory)mindSlaveMaster.getLobbyPlayer();
            controller = master.createMindSlaveController(mindSlaveMaster, this);
            game.fireEvent(new GameEventPlayerControl(this, oldLobbyPlayer, oldController, getLobbyPlayer(), controller));
        } else {
            controller = controllerCreator;
            game.fireEvent(new GameEventPlayerControl(this, getLobbyPlayer(), controller, null, null));
        }
    }

    private void setOutcome(PlayerOutcome outcome) {
        stats.setOutcome(outcome);
    }

    public void onGameOver() {
        if (null == stats.getOutcome()) {
            setOutcome(PlayerOutcome.win());
        }
    }

    /**
     * use to get a list of creatures in play for a given player.
     */
    public CardCollection getCreaturesInPlay() {
        return CardLists.filter(getCardsIn(ZoneType.Battlefield), Presets.CREATURES);
    }

    /**
     * use to get a list of all lands a given player has on the battlefield.
     */
    public CardCollection getLandsInPlay() {
        return CardLists.filter(getCardsIn(ZoneType.Battlefield), Presets.LANDS);
    }

    public boolean isCardInPlay(final String cardName) {
        return getZone(ZoneType.Battlefield).contains(CardPredicates.nameEquals(cardName));
    }

    public boolean isCardInCommand(final String cardName) {
        return getZone(ZoneType.Command).contains(CardPredicates.nameEquals(cardName));
    }

    public CardCollectionView getColoredCardsInPlay(final String color) {
        return CardLists.getColor(getCardsIn(ZoneType.Battlefield), MagicColor.fromName(color));
    }

    public CardCollectionView getColoredCardsInPlay(final byte color) {
        return CardLists.getColor(getCardsIn(ZoneType.Battlefield), color);
    }

    public final int getAmountOfKeyword(final String k) {
        return keywords.getAmount(k);
    }

    public void onCleanupPhase() {
        for (Card c : getCardsIn(ZoneType.Hand)) {
            c.setDrawnThisTurn(false);
        }
        resetPreventNextDamage();
        resetPreventNextDamageWithEffect();
        resetNumDrawnThisTurn();
        resetNumDiscardedThisTurn();
        setNumCardsInHandStartedThisTurnWith(getCardsIn(ZoneType.Hand).size());
        setAttackedWithCreatureThisTurn(false);
        setActivateLoyaltyAbilityThisTurn(false);
        setTappedLandForManaThisTurn(false);
        setLandsPlayedLastTurn(getLandsPlayedThisTurn());
        resetLandsPlayedThisTurn();
        resetInvestigatedThisTurn();
        resetSacrificedThisTurn();
        resetCounterToPermThisTurn();
        clearAssignedDamage();
        resetAttackersDeclaredThisTurn();
        setRevolt(false);
    }

    public boolean canCastSorcery() {
        PhaseHandler now = game.getPhaseHandler();
        if(now.getPhase() == null || game.getStack() == null) {
            return false;
        }
        return now.isPlayerTurn(this) && now.getPhase().isMain() && game.getStack().isEmpty();
    }

    //NOTE: for conditions the stack must only have the sa being checked
    public boolean couldCastSorcery(final SpellAbility sa) {
        final Card source = sa.getRootAbility().getHostCard();
        boolean onlyThis = true;

        for (final Card card : game.getCardsIn(ZoneType.Stack)) {
            if (!card.equals(source)) {
                onlyThis = false;
                //System.out.println("StackCard: " + card + " vs SourceCard: " + source);
            }
        }

        PhaseHandler now = game.getPhaseHandler();
        //System.out.println("now.isPlayerTurn(player) - " + now.isPlayerTurn(player));
        //System.out.println("now.getPhase().isMain() - " + now.getPhase().isMain());
        //System.out.println("onlyThis - " + onlyThis);
        return onlyThis && now.isPlayerTurn(this) && now.getPhase().isMain();
    }

    public final PlayerController getController() {
        return controller;
    }

    public final void setFirstController(PlayerController ctrlr) {
        if (controllerCreator != null) {
            throw new IllegalStateException("Controller creator already assigned");
        }
        controllerCreator = ctrlr;
        controller = ctrlr;
        updateAvatar();
        view.updateIsAI(this);
        view.updateLobbyPlayerName(this);
    }

    public void updateAvatar() {
        view.updateAvatarIndex(this);
        view.updateAvatarCardImageKey(this);
    }

    /**
     * Run a procedure using a different controller
     */
    public void runWithController(Runnable proc, PlayerController tempController) {
        PlayerController oldController = controller;
        controller = tempController;
        try {
            proc.run();
        } finally {
            controller = oldController;
        }
    }

    public boolean isSkippingCombat() {
        if (hasKeyword("Skip your next combat phase.")) {
            return true;
        }
        if (hasKeyword("Skip your combat phase.")) {
            return true;
        }
        if (hasKeyword("Skip all combat phases of your next turn.")) {
            replaceAllKeywordInstances("Skip all combat phases of your next turn.",
                    "Skip all combat phases of this turn.");
            return true;
        }
        if (hasKeyword("Skip all combat phases of this turn.")) {
            return true;
        }
        return false;
    }

    public boolean isSkippingMain() {
        return hasKeyword("Skip your main phase.");
    }

    public int getStartingHandSize() {
        return startingHandSize;
    }

    public void setStartingHandSize(int shs) {
        startingHandSize = shs;
    }

    /**
     * Takes the top plane of the planar deck and put it face up in the command zone.
     * Then runs triggers.
     */
    public void planeswalk() {
        planeswalkTo(new CardCollection(getZone(ZoneType.PlanarDeck).get(0)));
    }

    /**
     * Puts the planes in the argument and puts them face up in the command zone.
     * Then runs triggers.
     */
    public void planeswalkTo(final CardCollectionView destinations) {
        System.out.println(getName() + ": planeswalk to " + destinations.toString());
        currentPlanes.addAll(destinations);
        game.getView().updatePlanarPlayer(getView());

        for (Card c : currentPlanes) {
            game.getAction().moveTo(ZoneType.Command,c, null, null);
            //getZone(ZoneType.PlanarDeck).remove(c);
            //getZone(ZoneType.Command).add(c);
        }

        //DBG
        //System.out.println("CurrentPlanes: " + currentPlanes);
        //System.out.println("ActivePlanes: " + game.getActivePlanes());
        //System.out.println("CommandPlanes: " + getZone(ZoneType.Command).getCards());

        game.setActivePlanes(currentPlanes);
        //Run PlaneswalkedTo triggers here.
        Map<String,Object> runParams = Maps.newHashMap();
        runParams.put("Cards", currentPlanes);
        game.getTriggerHandler().runTrigger(TriggerType.PlaneswalkedTo, runParams,false);
        view.updateCurrentPlaneName(currentPlanes.toString().replaceAll(" \\(.*","").replace("[",""));
    }

    /**
     * Puts my currently active planes, if any, at the bottom of my planar deck.
     */
    public void leaveCurrentPlane() {

        final Map<String, Object> runParams = new ImmutableMap.Builder<String, Object>().put("Cards", new CardCollection(currentPlanes)).build();
        game.getTriggerHandler().runTrigger(TriggerType.PlaneswalkedFrom, runParams,false);

        for (final Card plane : currentPlanes) {
            //game.getZoneOf(plane).remove(plane);
            plane.clearControllers();
            //getZone(ZoneType.PlanarDeck).add(plane);
            game.getAction().moveTo(ZoneType.PlanarDeck, plane,-1, null, null);
        }
        currentPlanes.clear();

        //DBG
        //System.out.println("CurrentPlanes: " + currentPlanes);
        //System.out.println("ActivePlanes: " + game.getActivePlanes());
        //System.out.println("CommandPlanes: " + getZone(ZoneType.Command).getCards());
    }

    /**
     * Sets up the first plane of a round.
     */
    public void initPlane() {
        Card firstPlane = null;
        view.updateCurrentPlaneName("");
        game.getView().updatePlanarPlayer(getView());

        while (true) {
            firstPlane = getZone(ZoneType.PlanarDeck).get(0);
            getZone(ZoneType.PlanarDeck).remove(firstPlane);
            if (firstPlane.getType().isPhenomenon()) {
                getZone(ZoneType.PlanarDeck).add(firstPlane);
            }
            else {
                currentPlanes.add(firstPlane);
                getZone(ZoneType.Command).add(firstPlane);
                break;
            }
        }
        game.setActivePlanes(currentPlanes);
        view.updateCurrentPlaneName(currentPlanes.toString().replaceAll(" \\(.*","").replace("[",""));
    }

    public final void resetCombatantsThisCombat() {
        // resets the status of attacked/blocked this phase
        CardCollectionView list = getCardsIn(ZoneType.Battlefield);

        for (Card c : list) {
            if (c.getDamageHistory().getCreatureAttackedThisCombat()) {
                c.getDamageHistory().setCreatureAttackedThisCombat(false);
            }
            if (c.getDamageHistory().getCreatureBlockedThisCombat()) {
                c.getDamageHistory().setCreatureBlockedThisCombat(false);
            }

            if (c.getDamageHistory().getCreatureGotBlockedThisCombat()) {
                c.getDamageHistory().setCreatureGotBlockedThisCombat(false);
            }
        }
    }

    public boolean isSkippingDraw() {
        if (hasKeyword("Skip your next draw step.")) {
            removeKeyword("Skip your next draw step.");
            return true;
        }
        if (hasKeyword("Skip your draw step.")) {
            return true;
        }
        return false;
    }

    public CardCollectionView getInboundTokens() {
        return inboundTokens;
    }
    
    public void addInboundToken(Card c) {
        inboundTokens.add(c);
    }

    public void removeInboundToken(Card c) {
        inboundTokens.remove(c);
    }

    public void onMulliganned() {
        game.fireEvent(new GameEventMulligan(this)); // quest listener may interfere here
        final int newHand = getCardsIn(ZoneType.Hand).size();
        stats.notifyHasMulliganed();
        stats.notifyOpeningHandSize(newHand);
        achievementTracker.mulliganTo = newHand;
    }

    public List<Card> getCommanders() {
        return commanders;
    }
    public void setCommanders(List<Card> commander0) {
    	if (commander0 == commanders) { return; }
        commanders = commander0;
        view.updateCommander(this);
    }

    public Iterable<Entry<Card, Integer>> getCommanderDamage() {
        return commanderDamage.entrySet();
    }
    public int getCommanderDamage(Card commander) {
        Integer damage = commanderDamage.get(commander);
        return damage == null ? 0 : damage.intValue();
    }

    public int getCommanderCast(Card commander) {
        Integer cast = commanderCast.get(commander);
        return cast == null ? 0 : cast.intValue();
    }

    public Iterable<Entry<Card, Integer>> getCommanderCast() {
        return commanderCast.entrySet();
    }

    public void incCommanderCast(Card commander) {
        commanderCast.put(commander, getCommanderCast(commander) + 1);
        view.updateCommanderCast(this);
    }

    public boolean isPlayingExtraTurn() {
        return isPlayingExtraTrun;
    }
    public void setExtraTurn(boolean b) {
        isPlayingExtraTrun  = b;
    }

    public void addExtraTurn() {
    	extraTurnNum++;
    	view.updateExtraTurn(this);
    }

    public void useExtraTurn() {
    	extraTurnNum--;
    	view.updateExtraTurn(this);
    }

    public int getExtraTurn() {
    	return extraTurnNum;
    }

    public void initVariantsZones(RegisteredPlayer registeredPlayer) {
        PlayerZone bf = getZone(ZoneType.Battlefield);
        Iterable<? extends IPaperCard> cards = registeredPlayer.getCardsOnBattlefield();
        if (cards != null) {
            for (final IPaperCard cp : cards) {
                Card c = Card.fromPaperCard(cp, this);
                bf.add(c);
                c.setSickness(true);
                c.setStartsGameInPlay(true);
            }
        }

        PlayerZone com = getZone(ZoneType.Command);

        // Vanguard
        if (registeredPlayer.getVanguardAvatars() != null) {
            for(PaperCard avatar:registeredPlayer.getVanguardAvatars()) {
                com.add(Card.fromPaperCard(avatar, this));
            }
        }
    
        // Schemes
        CardCollection sd = new CardCollection();
        for (IPaperCard cp : registeredPlayer.getSchemes()) {
            sd.add(Card.fromPaperCard(cp, this));
        }
        if (!sd.isEmpty()) {
            for (Card c : sd) {
                getZone(ZoneType.SchemeDeck).add(c);
            }
            getZone(ZoneType.SchemeDeck).shuffle();
        }

        // Planes
        CardCollection l = new CardCollection();
        for (IPaperCard cp : registeredPlayer.getPlanes()) {
            l.add(Card.fromPaperCard(cp, this));
        }
        if (!l.isEmpty()) {
            for (Card c : l) {
                getZone(ZoneType.PlanarDeck).add(c);
            }
            getZone(ZoneType.PlanarDeck).shuffle();
        }

        // Commander
        if (!registeredPlayer.getCommanders().isEmpty()) {
            List<Card> commanders = Lists.newArrayList();
            for (PaperCard pc : registeredPlayer.getCommanders()) {
                Card cmd = Card.fromPaperCard(pc, this);
                cmd.setCommander(true);
                com.add(cmd);
                commanders.add(cmd);
                com.add(createCommanderEffect(game, cmd));
            }
            this.setCommanders(commanders);
        }
        else if (registeredPlayer.getPlaneswalker() != null) { // Planeswalker
            Card cmd = Card.fromPaperCard(registeredPlayer.getPlaneswalker(), this);
            cmd.setCommander(true);
            com.add(cmd);
            setCommanders(Lists.newArrayList(cmd));
            com.add(createCommanderEffect(game, cmd));
        }

        // Conspiracies
        for (IPaperCard cp : registeredPlayer.getConspiracies()) {
            Card conspire = Card.fromPaperCard(cp, this);
            if (conspire.hasKeyword("Hidden agenda")) {
                if (!CardFactoryUtil.handleHiddenAgenda(this, conspire)) {
                    continue;
                }
            }
            com.add(conspire);
        }
    }

    public static DetachedCardEffect createCommanderEffect(Game game, Card commander) {
        final String name = Lang.getPossesive(commander.getName()) + " Commander Effect";
        DetachedCardEffect eff = new DetachedCardEffect(commander, name);

        eff.setSVar("CommanderMoveReplacement", "DB$ ChangeZone | Origin$ Battlefield,Graveyard,Exile,Library,Hand | Destination$ Command | Defined$ ReplacedCard");

        String moved = "Event$ Moved | ValidCard$ Card.EffectSource+YouOwn | Secondary$ True | Optional$ True | OptionalDecider$ You | ReplaceWith$ CommanderMoveReplacement "; 
        if (game.getRules().hasAppliedVariant(GameType.TinyLeaders)) {
            moved += " | Destination$ Graveyard,Exile | Description$ If a commander would be put into its owner's graveyard or exile from anywhere, that player may put it into the command zone instead.";
        } else {
            moved += " | Destination$ Graveyard,Exile,Hand,Library | Description$ If a commander would be exiled or put into hand, graveyard, or library from anywhere, that player may put it into the command zone instead.";
        }
        eff.addReplacementEffect(ReplacementHandler.parseReplacement(moved, eff, true));

        String mayBePlayedAbility = "Mode$ Continuous | EffectZone$ Command | MayPlay$ True | Affected$ Card.YouOwn+EffectSource | AffectedZone$ Command";
        if (game.getRules().hasAppliedVariant(GameType.Planeswalker)) { //support paying for Planeswalker with any color mana
            mayBePlayedAbility += " | MayPlayIgnoreColor$ True";
        }
        eff.addStaticAbility(mayBePlayedAbility);
        eff.getCurrentState().setImageKey(commander.getImageKey());
        return eff;
    }

    public void changeOwnership(Card card) {
        // If lost then gained, just clear out of lost.
        // If gained then lost, just clear out of gained.
        Player oldOwner = card.getOwner();

        if (equals(oldOwner)) {
            return;
        }
        card.setOwner(this);

        if (lostOwnership.contains(card)) {
            lostOwnership.remove(card);
        }
        else {
            gainedOwnership.add(card);
        }

        if (oldOwner.gainedOwnership.contains(card)) {
            oldOwner.gainedOwnership.remove(card);
        }
        else {
            oldOwner.lostOwnership.add(card);
        }
    }

    public CardCollectionView getLostOwnership() {
        return lostOwnership;
    }

    public CardCollectionView getGainedOwnership() {
        return gainedOwnership;
    }

    @Override
    public PlayerView getView() {
        return view;
    }

    public void createMonarchEffect() {
        final PlayerZone com = getZone(ZoneType.Command);
        if (monarchEffect == null) {
            monarchEffect = new Card(game.nextCardId(), game);
            monarchEffect.setOwner(this);
            monarchEffect.setImageKey("t:monarch");
            monarchEffect.setName("The Monarch");
            monarchEffect.addType("Effect");

            {
                final String drawTrig = "Mode$ Phase | Phase$ End of Turn | TriggerZones$ Command | " + 
                "ValidPlayer$ You |  TriggerDescription$ At the beginning of your end step, draw a card.";
                final String drawEff = "AB$Draw | Cost$ 0 | Defined$ You | NumCards$ 1";

                final Trigger drawTrigger = TriggerHandler.parseTrigger(drawTrig, monarchEffect, true);

                drawTrigger.setOverridingAbility(AbilityFactory.getAbility(drawEff, monarchEffect));
                monarchEffect.addTrigger(drawTrigger);
            }

            {
                final String damageTrig = "Mode$ DamageDone | ValidSource$ Creature | ValidTarget$ You | CombatDamage$ True | TriggerZones$ Command |" +
                " TriggerDescription$ Whenever a creature deals combat damage to you, its controller becomes the monarch.";
                final String damageEff = "AB$BecomeMonarch | Cost$ 0 | Defined$ TriggeredSourceController";

                final Trigger damageTrigger = TriggerHandler.parseTrigger(damageTrig, monarchEffect, true);

                damageTrigger.setOverridingAbility(AbilityFactory.getAbility(damageEff, monarchEffect));
                monarchEffect.addTrigger(damageTrigger);
            }
            monarchEffect.updateStateForView();
        }
        com.add(monarchEffect);

        this.updateZoneForView(com);
    }
    public void removeMonarchEffect() {
        final PlayerZone com = getZone(ZoneType.Command);
        if (monarchEffect != null) {
            com.remove(monarchEffect);
            this.updateZoneForView(com);
        }
    }
    
    public boolean hasBlessing() {
        return blessingEffect != null;
    }
    
    public void setBlessing(boolean bless) {
        // no need to to change
        if ((blessingEffect != null) == bless) {
            return;
        }

        final PlayerZone com = getZone(ZoneType.Command);
        
        if(bless) {
            blessingEffect = new Card(game.nextCardId(), game);
            blessingEffect.setOwner(this);
            blessingEffect.setImageKey("t:blessing");
            blessingEffect.setName("City's Blessing");
            blessingEffect.addType("Effect");
            

            blessingEffect.updateStateForView();

            com.add(blessingEffect);
        } else {
            com.remove(blessingEffect);
            blessingEffect = null;
        }
        
        this.updateZoneForView(com);
    }

    public final int countExaltedBonus() {
        return CardLists.getAmountOfKeyword(this.getCardsIn(ZoneType.Battlefield), Keyword.EXALTED);
    }
}
