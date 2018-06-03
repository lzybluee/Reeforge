package forge.limited;

public enum LimitedPoolType { 
    Block("Block / Set"),
    Full("Full Cardpool"),
    Format("Constructed Format"),
    Custom("Custom Cube"),
    FantasyBlock("Fantasy Block");
    
    private final String displayName;
    private LimitedPoolType(String name) {
        displayName = name;
    }

    @Override
    public String toString() {
        return displayName;
    }
}