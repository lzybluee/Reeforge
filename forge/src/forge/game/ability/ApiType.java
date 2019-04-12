package forge.game.ability;


import forge.game.ability.effects.*;
import forge.util.ReflectionUtil;

import java.util.HashMap;
import java.util.Map;

/** 
 * TODO: Write javadoc for this type.
 *
 */
public enum ApiType {
    Abandon (AbandonEffect.class),
    ActivateAbility (ActivateAbilityEffect.class),
    AddOrRemoveCounter (CountersPutOrRemoveEffect.class),
    AddPhase (AddPhaseEffect.class),
    AddTurn (AddTurnEffect.class),
    Animate (AnimateEffect.class),
    AnimateAll (AnimateAllEffect.class),
    Attach (AttachEffect.class),
    Ascend (AscendEffect.class),
    Balance (BalanceEffect.class),
    BecomeMonarch (BecomeMonarchEffect.class),
    BecomesBlocked (BecomesBlockedEffect.class),
    BidLife (BidLifeEffect.class),
    Bond (BondEffect.class),
    Branch (BranchEffect.class),
    ChangeCombatants (ChangeCombatantsEffect.class),
    ChangeTargets (ChangeTargetsEffect.class),
    ChangeText (ChangeTextEffect.class),
    ChangeZone (ChangeZoneEffect.class),
    ChangeZoneAll (ChangeZoneAllEffect.class),
    Charm (CharmEffect.class),
    ChooseCard (ChooseCardEffect.class),
    ChooseColor (ChooseColorEffect.class),
    ChooseDirection (ChooseDirectionEffect.class),
    ChooseNumber (ChooseNumberEffect.class),
    ChoosePlayer (ChoosePlayerEffect.class),
    ChooseSource (ChooseSourceEffect.class),
    ChooseType (ChooseTypeEffect.class),
    Clash (ClashEffect.class),
    Cleanup (CleanUpEffect.class),
    Clone (CloneEffect.class),
    CopyPermanent (CopyPermanentEffect.class),
    CopySpellAbility (CopySpellAbilityEffect.class),
    ControlSpell (ControlSpellEffect.class),
    ControlPlayer (ControlPlayerEffect.class),
    Counter (CounterEffect.class),
    DamageAll (DamageAllEffect.class),
    DealDamage (DamageDealEffect.class),
    Debuff (DebuffEffect.class),
    DeclareCombatants (DeclareCombatantsEffect.class),
    DelayedTrigger (DelayedTriggerEffect.class),
    Destroy (DestroyEffect.class),
    DestroyAll (DestroyAllEffect.class),
    Dig (DigEffect.class),
    DigUntil (DigUntilEffect.class),
    Discard (DiscardEffect.class),
    DrainMana (DrainManaEffect.class),
    Draw (DrawEffect.class),
    EachDamage (DamageEachEffect.class),
    Effect (EffectEffect.class),
    Encode (EncodeEffect.class),
    EndTurn (EndTurnEffect.class),
    ExchangeLife (LifeExchangeEffect.class),
    ExchangeLifeVariant (LifeExchangeVariantEffect.class),
    ExchangeControl (ControlExchangeEffect.class),
    ExchangeControlVariant (ControlExchangeVariantEffect.class),
    ExchangePower (PowerExchangeEffect.class),
    ExchangeZone (ZoneExchangeEffect.class),
    Explore (ExploreEffect.class),
    Fight (FightEffect.class),
    FlipACoin (FlipCoinEffect.class),
    Fog (FogEffect.class),
    GainControl (ControlGainEffect.class),
    GainLife (LifeGainEffect.class),
    GainOwnership (OwnershipGainEffect.class),
    GameDrawn (GameDrawEffect.class),
    GenericChoice (ChooseGenericEffect.class),
    Goad (GoadEffect.class),
    Haunt (HauntEffect.class),
    ImmediateTrigger (ImmediateTriggerEffect.class),
    LookAt (LookAtEffect.class),
    LoseLife (LifeLoseEffect.class),
    LosesGame (GameLossEffect.class),
    Mana (ManaEffect.class),
    ManaReflected (ManaReflectedEffect.class),
    Manifest (ManifestEffect.class),
    Meld (MeldEffect.class),
    Mill (MillEffect.class),
    MoveCounter (CountersMoveEffect.class),
    MultiplePiles (MultiplePilesEffect.class),
    MultiplyCounter (CountersMultiplyEffect.class),
    MustAttack (MustAttackEffect.class),
    MustBlock (MustBlockEffect.class),
    NameCard (ChooseCardNameEffect.class),
    NoteCounters (CountersNoteEffect.class),
    PeekAndReveal (PeekAndRevealEffect.class),
    PermanentCreature (PermanentCreatureEffect.class),
    PermanentNoncreature (PermanentNoncreatureEffect.class),
    Phases (PhasesEffect.class),
    Planeswalk (PlaneswalkEffect.class),
    Play (PlayEffect.class),
    PlayLandVariant (PlayLandVariantEffect.class),
    Poison (PoisonEffect.class),
    PreventDamage (DamagePreventEffect.class),
    PreventDamageAll (DamagePreventAllEffect.class),
    Proliferate (CountersProliferateEffect.class),
    Protection (ProtectEffect.class),
    ProtectionAll (ProtectAllEffect.class),
    Pump (PumpEffect.class),
    PumpAll (PumpAllEffect.class),
    PutCounter (CountersPutEffect.class),
    PutCounterAll (CountersPutAllEffect.class),
    RearrangeTopOfLibrary (RearrangeTopOfLibraryEffect.class),
    Regenerate (RegenerateEffect.class),
    RegenerateAll (RegenerateAllEffect.class),
    Regeneration (RegenerationEffect.class),
    RemoveCounter (CountersRemoveEffect.class),
    RemoveCounterAll (CountersRemoveAllEffect.class),
    RemoveFromCombat (RemoveFromCombatEffect.class),
    ReorderZone (ReorderZoneEffect.class),
    Repeat (RepeatEffect.class),
    RepeatEach (RepeatEachEffect.class),
    ReplaceEffect (ReplaceEffect.class),
    ReplaceDamage (ReplaceDamageEffect.class),
    ReplaceSplitDamage (ReplaceSplitDamageEffect.class),
    RestartGame (RestartGameEffect.class),
    Reveal (RevealEffect.class),
    RevealHand (RevealHandEffect.class),
    ReverseTurnOrder (ReverseTurnOrderEffect.class),
    RollPlanarDice (RollPlanarDiceEffect.class),
    RunSVarAbility (RunSVarAbilityEffect.class),
    Sacrifice (SacrificeEffect.class),
    SacrificeAll (SacrificeAllEffect.class),
    Scry (ScryEffect.class),
    SetInMotion (SetInMotionEffect.class),
    SetLife (LifeSetEffect.class),
    SetState (SetStateEffect.class),
    Shuffle (ShuffleEffect.class),
    SkipTurn (SkipTurnEffect.class),
    StoreSVar (StoreSVarEffect.class),
    StoreMap (StoreMapEffect.class),
    Surveil (SurveilEffect.class),
    Tap (TapEffect.class),
    TapAll (TapAllEffect.class),
    TapOrUntap (TapOrUntapEffect.class),
    TapOrUntapAll (TapOrUntapAllEffect.class),
    Token (TokenEffect.class, false),
    TwoPiles (TwoPilesEffect.class),
    Unattach (UnattachEffect.class),
    UnattachAll (UnattachAllEffect.class),
    Untap (UntapEffect.class),
    UntapAll (UntapAllEffect.class),
    Vote (VoteEffect.class),
    WinsGame (GameWinEffect.class),


    DamageResolve (DamageResolveEffect.class),
    InternalEtbReplacement (ETBReplacementEffect.class),
    InternalLegendaryRule (CharmEffect.class),
    InternalIgnoreEffect (CharmEffect.class);


    private final SpellAbilityEffect instanceEffect;
    private final Class<? extends SpellAbilityEffect> clsEffect;

    private static final Map<String, ApiType> allValues = new HashMap<>();
    
    static {
    	for(ApiType t : ApiType.values()) {
    		allValues.put(t.name().toLowerCase(), t);
    	}
    }

    ApiType(Class<? extends SpellAbilityEffect> clsEf) { this(clsEf, true); }
    ApiType(Class<? extends SpellAbilityEffect> clsEf, final boolean isStateLess) {
        clsEffect = clsEf;
        instanceEffect = isStateLess ? ReflectionUtil.makeDefaultInstanceOf(clsEf) : null; 
    }

    public static ApiType smartValueOf(String value) {
        ApiType v = allValues.get(value.toLowerCase());
        if ( v == null )
            throw new RuntimeException("Element " + value + " not found in ApiType enum");
        return v;
    }

    public SpellAbilityEffect getSpellEffect() {
        return instanceEffect != null ? instanceEffect : ReflectionUtil.makeDefaultInstanceOf(clsEffect);
    }
}
