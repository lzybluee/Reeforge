package forge.trackable;

import forge.card.CardRarity;
import forge.game.Direction;
import forge.game.GameType;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.trackable.TrackableTypes.TrackableType;

public enum TrackableProperty {
    //Shared
    Text(TrackableTypes.StringType),
    PreventNextDamage(TrackableTypes.IntegerType),
    EnchantedBy(TrackableTypes.CardViewCollectionType),
    Counters(TrackableTypes.CounterMapType),
    CurrentPlane(TrackableTypes.StringType),
    PlanarPlayer(TrackableTypes.PlayerViewType),

    //Card 
    Owner(TrackableTypes.PlayerViewType),
    Controller(TrackableTypes.PlayerViewType),
    Zone(TrackableTypes.EnumType(ZoneType.class)),
    Cloned(TrackableTypes.BooleanType),
    FlipCard(TrackableTypes.BooleanType),
    SplitCard(TrackableTypes.BooleanType),
    Attacking(TrackableTypes.BooleanType),
    Blocking(TrackableTypes.BooleanType),
    PhasedOut(TrackableTypes.BooleanType),
    Sickness(TrackableTypes.BooleanType),
    Tapped(TrackableTypes.BooleanType),
    Token(TrackableTypes.BooleanType),
    IsCommander(TrackableTypes.BooleanType),
    Damage(TrackableTypes.IntegerType),
    AssignedDamage(TrackableTypes.IntegerType),
    ShieldCount(TrackableTypes.IntegerType),
    ChosenType(TrackableTypes.StringType),
    ChosenColors(TrackableTypes.StringListType),
    ChosenCards(TrackableTypes.CardViewCollectionType),
    ChosenPlayer(TrackableTypes.PlayerViewType),
    ChosenDirection(TrackableTypes.EnumType(Direction.class)),
    ChosenMode(TrackableTypes.StringType),
    Remembered(TrackableTypes.StringType),
    NamedCard(TrackableTypes.StringType),
    PlayerMayLook(TrackableTypes.PlayerViewCollectionType, FreezeMode.IgnoresFreeze),
    PlayerMayLookTemp(TrackableTypes.PlayerViewCollectionType, FreezeMode.IgnoresFreeze),
    Equipping(TrackableTypes.CardViewType),
    EquippedBy(TrackableTypes.CardViewCollectionType),
    Enchanting(TrackableTypes.GameEntityViewType),
    Fortifying(TrackableTypes.CardViewType),
    FortifiedBy(TrackableTypes.CardViewCollectionType),
    EncodedCards(TrackableTypes.CardViewCollectionType),
    GainControlTargets(TrackableTypes.CardViewCollectionType),
    CloneOrigin(TrackableTypes.CardViewType),
    Cloner(TrackableTypes.StringType),
    ImprintedCards(TrackableTypes.CardViewCollectionType),
    HauntedBy(TrackableTypes.CardViewCollectionType),
    Haunting(TrackableTypes.CardViewType),
    MustBlockCards(TrackableTypes.CardViewCollectionType),
    PairedWith(TrackableTypes.CardViewType),
    CurrentState(TrackableTypes.CardStateViewType, FreezeMode.IgnoresFreezeIfUnset),
    AlternateState(TrackableTypes.CardStateViewType),
    HiddenId(TrackableTypes.IntegerType),
    ExertedThisTurn(TrackableTypes.BooleanType),

    //Card State
    Name(TrackableTypes.StringType),
    Colors(TrackableTypes.ColorSetType),
    ImageKey(TrackableTypes.StringType),
    Type(TrackableTypes.CardTypeViewType),
    ManaCost(TrackableTypes.ManaCostType),
    SetCode(TrackableTypes.StringType),
    Rarity(TrackableTypes.EnumType(CardRarity.class)),
    OracleText(TrackableTypes.StringType),
    RulesText(TrackableTypes.StringType),
    Power(TrackableTypes.IntegerType),
    Toughness(TrackableTypes.IntegerType),
    Loyalty(TrackableTypes.IntegerType),
    ChangedColorWords(TrackableTypes.StringMapType),
    ChangedTypes(TrackableTypes.StringMapType),
    HasDeathtouch(TrackableTypes.BooleanType),
    HasHaste(TrackableTypes.BooleanType),
    HasInfect(TrackableTypes.BooleanType),
    HasStorm(TrackableTypes.BooleanType),
    HasTrample(TrackableTypes.BooleanType),
    YouMayLook(TrackableTypes.BooleanType),
    OpponentMayLook(TrackableTypes.BooleanType),
    BlockAdditional(TrackableTypes.IntegerType),
    AbilityText(TrackableTypes.StringType),
    NonAbilityText(TrackableTypes.StringType),
    FoilIndex(TrackableTypes.IntegerType),

    //Player
    IsAI(TrackableTypes.BooleanType),
    LobbyPlayerName(TrackableTypes.StringType),
    AvatarIndex(TrackableTypes.IntegerType),
    AvatarCardImageKey(TrackableTypes.StringType),
    Opponents(TrackableTypes.PlayerViewCollectionType),
    Life(TrackableTypes.IntegerType),
    PoisonCounters(TrackableTypes.IntegerType),
    MaxHandSize(TrackableTypes.IntegerType),
    HasUnlimitedHandSize(TrackableTypes.BooleanType),
    NumDrawnThisTurn(TrackableTypes.IntegerType),
    Keywords(TrackableTypes.KeywordCollectionViewType, FreezeMode.IgnoresFreeze),
    Commander(TrackableTypes.CardViewCollectionType, FreezeMode.IgnoresFreeze),
    CommanderDamage(TrackableTypes.IntegerMapType),
    MindSlaveMaster(TrackableTypes.PlayerViewType),
    Ante(TrackableTypes.CardViewCollectionType, FreezeMode.IgnoresFreeze),
    Battlefield(TrackableTypes.CardViewCollectionType, FreezeMode.IgnoresFreeze), //zones can't respect freeze, otherwise cards that die from state based effects won't have that reflected in the UI
    Command(TrackableTypes.CardViewCollectionType, FreezeMode.IgnoresFreeze),
    Exile(TrackableTypes.CardViewCollectionType, FreezeMode.IgnoresFreeze),
    Flashback(TrackableTypes.CardViewCollectionType, FreezeMode.IgnoresFreeze),
    Graveyard(TrackableTypes.CardViewCollectionType, FreezeMode.IgnoresFreeze),
    Hand(TrackableTypes.CardViewCollectionType, FreezeMode.IgnoresFreeze),
    Library(TrackableTypes.CardViewCollectionType, FreezeMode.IgnoresFreeze),
    Mana(TrackableTypes.ManaMapType, FreezeMode.IgnoresFreeze),

    //SpellAbility
    HostCard(TrackableTypes.CardViewType),
    Description(TrackableTypes.StringType),
    CanPlay(TrackableTypes.BooleanType),
    PromptIfOnlyPossibleAbility(TrackableTypes.BooleanType),

    //StackItem
    Key(TrackableTypes.StringType),
    SourceTrigger(TrackableTypes.IntegerType),
    SourceCard(TrackableTypes.CardViewType),
    ActivatingPlayer(TrackableTypes.PlayerViewType),
    TargetCards(TrackableTypes.CardViewCollectionType),
    TargetPlayers(TrackableTypes.PlayerViewCollectionType),
    SubInstance(TrackableTypes.StackItemViewType),
    Ability(TrackableTypes.BooleanType),
    OptionalTrigger(TrackableTypes.BooleanType),

    //Combat
    AttackersWithDefenders(TrackableTypes.GenericMapType, FreezeMode.IgnoresFreeze),
    AttackersWithBlockers(TrackableTypes.GenericMapType, FreezeMode.IgnoresFreeze),
    BandsWithDefenders(TrackableTypes.GenericMapType, FreezeMode.IgnoresFreeze),
    BandsWithBlockers(TrackableTypes.GenericMapType, FreezeMode.IgnoresFreeze),
    AttackersWithPlannedBlockers(TrackableTypes.GenericMapType, FreezeMode.IgnoresFreeze),
    BandsWithPlannedBlockers(TrackableTypes.GenericMapType, FreezeMode.IgnoresFreeze),

    //Game
    Players(TrackableTypes.PlayerViewCollectionType),
    GameType(TrackableTypes.EnumType(GameType.class)),
    Title(TrackableTypes.StringType),
    Turn(TrackableTypes.IntegerType),
    WinningPlayerName(TrackableTypes.StringType),
    WinningTeam(TrackableTypes.IntegerType),
    MatchOver(TrackableTypes.BooleanType),
    NumGamesInMatch(TrackableTypes.IntegerType),
    NumPlayedGamesInMatch(TrackableTypes.IntegerType),
    Stack(TrackableTypes.StackItemViewListType),
    StormCount(TrackableTypes.IntegerType),
    GameOver(TrackableTypes.BooleanType),
    PoisonCountersToLose(TrackableTypes.IntegerType),
    GameLog(TrackableTypes.StringType),
    PlayerTurn(TrackableTypes.PlayerViewType),
    Phase(TrackableTypes.EnumType(PhaseType.class));

    public enum FreezeMode {
        IgnoresFreeze,
        RespectsFreeze,
        IgnoresFreezeIfUnset
    }

    private final TrackableType<?> type;
    private final FreezeMode freezeMode;

    private TrackableProperty(TrackableType<?> type0) {
        this(type0, FreezeMode.RespectsFreeze);
    }
    private TrackableProperty(TrackableType<?> type0, FreezeMode freezeMode0) {
        type = type0;
        freezeMode = freezeMode0;
    }

    public FreezeMode getFreezeMode() {
        return freezeMode;
    }

    @SuppressWarnings("unchecked")
    public <T> void updateObjLookup(Tracker tracker, T newObj) {
        ((TrackableType<T>)type).updateObjLookup(tracker, newObj);
    }

    public void copyChangedProps(TrackableObject from, TrackableObject to) {
        type.copyChangedProps(from, to, this);
    }

    @SuppressWarnings("unchecked")
    public <T> T getDefaultValue() {
        return ((TrackableType<T>)type).getDefaultValue();
    }
    @SuppressWarnings("unchecked")
    public <T> T deserialize(TrackableDeserializer td, T oldValue) {
        return ((TrackableType<T>)type).deserialize(td, oldValue);
    }
    @SuppressWarnings("unchecked")
    public <T> void serialize(TrackableSerializer ts, T value) {
        ((TrackableType<T>)type).serialize(ts, value);
    }

    //cache array of all properties to allow quick lookup by ordinal,
    //which reduces the size and improves performance of serialization
    //we don't need to worry about the values changing since we will ensure
    //both players are on the same version of Forge before allowing them to connect
    private static TrackableProperty[] props = values();
    public static int serialize(TrackableProperty prop) {
        return prop.ordinal();
    }
    public static TrackableProperty deserialize(int ordinal) {
        return props[ordinal];
    }
}
