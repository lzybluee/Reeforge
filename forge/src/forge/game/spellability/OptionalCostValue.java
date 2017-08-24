package forge.game.spellability;

import forge.game.cost.Cost;

public class OptionalCostValue {
    private OptionalCost type;
    private Cost cost;

    public OptionalCostValue(OptionalCost type, Cost cost) {
        this.type = type;
        this.cost = cost;
    }

    /**
     * @return the type
     */
    public OptionalCost getType() {
        return type;
    }

    /**
     * @param type the type to set
     */
    public void setType(OptionalCost type) {
        this.type = type;
    }

    /**
     * @return the cost
     */
    public Cost getCost() {
        return cost;
    }

    /**
     * @param cost the cost to set
     */
    public void setCost(Cost cost) {
        this.cost = cost;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.getName());
        sb.append(" ");
        sb.append(cost.toSimpleString());
        return sb.toString();
    }
}
