package schema;

import java.util.List;
import java.util.ArrayList;

public class Table {

    public String name;
    public int rowCount;
    public int blockCount;
    public int rowsPerBlock;
    public List<Attribute> attributes;
    public List<Index> indexes;
    
    public Attribute getAttribute(String attrName) {
        for (Attribute a: attributes) {
            if(a.name.equalsIgnoreCase(attrName)) return a;
        }
        return null;
    }
    
    public Index getBestIndexFor(String attrName) {
        Index best = null;
        for (Index i : indexes) {
            if(i.attributes.get(0).equalsIgnoreCase(attrName)) {
                if(best == null) best = i;
                else if (i.clustered && !best.clustered) best = i;
            }
        }
        return best;
    }

    // traži kompozitni indeks koji tačno odgovara listi prosleđenih kolona
    public Index getBestIndexForComposite(List<String> attrNames) {
        List<String> trazeneStriptovane = new ArrayList<>();
        for (String s : attrNames) {
            trazeneStriptovane.add(s.trim().toLowerCase());
        }

        for (Index i : indexes) {
            if (i.attributes.size() > 1) { // Kompozitni je samo ako ima više od jedne kolone
                List<String> koloneIndeksa = new ArrayList<>();
                for (String attr : i.attributes) {
                    koloneIndeksa.add(attr.trim().toLowerCase());
                }
                
                // Ako provera obuhvata sve elemente u oba smera (isti skup kolona)
                if (koloneIndeksa.containsAll(trazeneStriptovane) && trazeneStriptovane.containsAll(koloneIndeksa)) {
                    return i;
                }
            }
        }
        return null;
    }
}