package optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import schema.Attribute;
import schema.Index;
import schema.Schema;
import schema.Table;
import validator.ColumnExtractor;

/**
 * Akademski usklađen Cost-based optimizator.
 * Sadrži popravljene formule za BNLJ, SMJ, HJ, INLJ, 
 * ispravljenu procenu faktora blokiranja za međurezultate i tačan obračun Seek Cost-a.
 */
public class QueryOptimizer {

    private static final double SELEKTIVNOST_OPSEGA_BEZ_STATISTIKE = 0.5;

    // pomocne strukture

    private static class RelacijaStanje {
        String naziv;
        int rowCount;
        int blockCount;
        int rowsPerBlock;
        String alijasKljuc;
        boolean indeksDostupan; 
        boolean medjurezultatUMemoriji;

        RelacijaStanje(String naziv, int rowCount, int blockCount, int rowsPerBlock, String alijasKljuc, boolean indeksDostupan, boolean medjurezultatUMemoriji) {
            this.naziv = naziv;
            this.rowCount = rowCount;
            this.blockCount = blockCount;
            this.rowsPerBlock = rowsPerBlock;
            this.alijasKljuc = alijasKljuc;
            this.indeksDostupan = indeksDostupan;
            this.medjurezultatUMemoriji = medjurezultatUMemoriji;
        }

        RelacijaStanje kopiraj() {
            return new RelacijaStanje(naziv, rowCount, blockCount, rowsPerBlock, alijasKljuc, indeksDostupan, medjurezultatUMemoriji);
        }
    }

    private static class Cena {
        long bt, sc;
        String algoritam;
        boolean indeksRazmatran;
        Cena(long bt, long sc, String algoritam) { this.bt = bt; this.sc = sc; this.algoritam = algoritam; this.indeksRazmatran=false;}
        Cena(long bt, long sc, String algoritam,boolean indeksRazmatran) { this.bt = bt; this.sc = sc; this.algoritam = algoritam; this.indeksRazmatran=indeksRazmatran;}
    }

    private static class JoinUslov {
        String alijasL, alijasR;
        Column colL, colR;
    }

    // glavna metoda

    public static void optimize(PlainSelect queryBody, Schema schema) {
    	System.out.println("\n-------------------------------------------------------");
        System.out.println("     COST-BASED OPTIMIZATOR							     ");
        System.out.println("\n-------------------------------------------------------");

        int M = schema.bufferBlocks;
        Map<String, Table> aliasToTableMap = new HashMap<>();
        Map<String, RelacijaStanje> baznaStanja = new HashMap<>();

        // --- FAZA 0: Registracija tabela ---
        FromItem fromItem = queryBody.getFromItem();
        if (fromItem == null) return;

        String mainRealName = (fromItem instanceof net.sf.jsqlparser.schema.Table)
                ? ((net.sf.jsqlparser.schema.Table) fromItem).getName() : fromItem.toString();
        Table mainTable = schema.getTable(mainRealName);
        String defaultTableKey = (fromItem.getAlias() != null) ? fromItem.getAlias().getName().toLowerCase() : mainRealName.toLowerCase();

        if (mainTable != null) {
            aliasToTableMap.put(defaultTableKey, mainTable);
            baznaStanja.put(defaultTableKey, new RelacijaStanje(mainTable.name, mainTable.rowCount, mainTable.blockCount, mainTable.rowsPerBlock, defaultTableKey, true, false));
        }

        if (queryBody.getJoins() != null) {
            for (Join join : queryBody.getJoins()) {
                FromItem joinItem = join.getRightItem();
                String joinRealName = (joinItem instanceof net.sf.jsqlparser.schema.Table)
                        ? ((net.sf.jsqlparser.schema.Table) joinItem).getName() : joinItem.toString();
                Table joinTable = schema.getTable(joinRealName);
                if (joinTable != null) {
                    String kljuc = (joinItem.getAlias() != null) ? joinItem.getAlias().getName().toLowerCase() : joinRealName.toLowerCase();
                    aliasToTableMap.put(kljuc, joinTable);
                    baznaStanja.put(kljuc, new RelacijaStanje(joinTable.name, joinTable.rowCount, joinTable.blockCount, joinTable.rowsPerBlock, kljuc, true, false));
                }
            }
        }

        // razbijanje where klauzule
        List<Expression> sveKonjunkcije = new ArrayList<>();
        if (queryBody.getWhere() != null) razbijKonjunkciju(queryBody.getWhere(), sveKonjunkcije);

        Map<String, List<Expression>> lokalneSelekcijePoAlijasu = new HashMap<>();
        List<JoinUslov> sviJoinUslovi = new ArrayList<>();

        for (Expression uslov : sveKonjunkcije) {
            List<Column> kolone = ColumnExtractor.getColumns(uslov);
            if (kolone.isEmpty()) continue;

            List<String> ukljuceniAlijasi = new ArrayList<>();
            for (Column col : kolone) {
                String alijas = (col.getTable() != null) ? col.getTable().getName().toLowerCase() : defaultTableKey;
                if (!ukljuceniAlijasi.contains(alijas)) ukljuceniAlijasi.add(alijas);
            }

            if (ukljuceniAlijasi.size() == 1) {
                lokalneSelekcijePoAlijasu.computeIfAbsent(ukljuceniAlijasi.get(0), k -> new ArrayList<>()).add(uslov);
            } else if (ukljuceniAlijasi.size() == 2 && uslov instanceof EqualsTo) {
                EqualsTo eq = (EqualsTo) uslov;
                JoinUslov ju = new JoinUslov();
                ju.colL = (Column) eq.getLeftExpression();
                ju.colR = (Column) eq.getRightExpression();
                ju.alijasL = (ju.colL.getTable() != null) ? ju.colL.getTable().getName().toLowerCase() : defaultTableKey;
                ju.alijasR = (ju.colR.getTable() != null) ? ju.colR.getTable().getName().toLowerCase() : defaultTableKey;
                sviJoinUslovi.add(ju);
            }
        }

        // pushdown selekcije ili odlozena selekcija
        System.out.println("\n--- Faza 2: Pushdown vs Odlozena selekcija ---");

        Map<String, RelacijaStanje> konacnaStanjaZaJoin = new HashMap<>();
        Map<String, List<Expression>> odlozeniUslovi = new HashMap<>();
        long fiksniTrosakSelekcijaBT = 0, fiksniTrosakSelekcijaSC = 0;

        for (String alijas : baznaStanja.keySet()) {
            RelacijaStanje bazno = baznaStanja.get(alijas);
            List<Expression> uslovi = lokalneSelekcijePoAlijasu.get(alijas);

            if (uslovi == null || uslovi.isEmpty()) {
                konacnaStanjaZaJoin.put(alijas, bazno.kopiraj());
                continue;
            }

            Table stvarnaTabela = aliasToTableMap.get(alijas);

            Cena cenaA = evaluirajUslove(bazno, stvarnaTabela, uslovi);
            double selektivnostSvih = kombinovanaSelektivnost(stvarnaTabela, uslovi);
            int rowsA = Math.max(1, (int) Math.ceil(bazno.rowCount * selektivnostSvih));
            int blocksA = Math.max(1, (int) Math.ceil((double) rowsA / bazno.rowsPerBlock));

            RelacijaStanje stanjeA = bazno.kopiraj();
            stanjeA.rowCount = rowsA;
            stanjeA.blockCount = blocksA;
            stanjeA.indeksDostupan = false;
            stanjeA.medjurezultatUMemoriji = (blocksA <= M);

            RelacijaStanje stanjeB = bazno.kopiraj();

            //String ispisIndeksa = cenaA.algoritam.contains("indeks") ? "KORISCEN INDEKS" : "NEMA INDEKSA";
            String ispisIndeksa = "";
            if(cenaA.algoritam.contains("indeks")) {
            	ispisIndeksa = "KORISCEN INDEKS";
            }else if(cenaA.indeksRazmatran) {
            	ispisIndeksa = "INDEKS ODBACEN - LINEARNA PRETRAGA JEFTINIJA";
            }else {
            	ispisIndeksa = "TABELA NEMA ADEKVATAN INDEKS";
            }
            System.out.println(" -> " + bazno.naziv + " (" + alijas + "):");
            System.out.println("    * Opcija A (Pushdown): Cena = " + cenaA.bt + " BT, " + cenaA.sc + " SC. Rezultat: " + blocksA + " blokova (" + ispisIndeksa + ")");
            System.out.println("    * Opcija B (Odlozeno): Cena = 0 BT (pipelining), " + bazno.blockCount + " blokova ostaje za JOIN (indeks zadrzan)");

            boolean postojiKorisniIndeksZaJoin = false;
            for (JoinUslov ju : sviJoinUslovi) {
                if (ju.alijasL.equals(alijas) || ju.alijasR.equals(alijas)) {
                    Column kolonaSpajanja = ju.alijasL.equals(alijas) ? ju.colL : ju.colR;
                    Attribute a = stvarnaTabela.getAttribute(kolonaSpajanja.getColumnName());
                    if (a != null && stvarnaTabela.getBestIndexFor(a.name) != null) { postojiKorisniIndeksZaJoin = true; break; }
                }
            }

            boolean biramoOdlozeno = postojiKorisniIndeksZaJoin && proceniDaLiJeOdlaganjeIsplativije(
                    stanjeA, stanjeB, cenaA, alijas, sviJoinUslovi, aliasToTableMap, baznaStanja, konacnaStanjaZaJoin, M);

            if (biramoOdlozeno) {
                System.out.println("    ==> [ODLUKA]: Opcija B (odlozeno) - zadrzavanje indeksa za JOIN se isplati.");
                konacnaStanjaZaJoin.put(alijas, stanjeB);
                odlozeniUslovi.put(alijas, uslovi);
            } else {
                System.out.println("    ==> [ODLUKA]: Opcija A (pushdown) - manji ulaz u JOIN je jeftiniji.");
                konacnaStanjaZaJoin.put(alijas, stanjeA);
                fiksniTrosakSelekcijaBT += cenaA.bt;
                fiksniTrosakSelekcijaSC += cenaA.sc;
            }
        }

        // greedy join enumeracija
        System.out.println("\n--- Faza 3: Izvrsavanje spajanja ---");
        long totalBT = fiksniTrosakSelekcijaBT, totalSC = fiksniTrosakSelekcijaSC;

        Map<Set<String>, RelacijaStanje> aktivniPlanovi = new HashMap<>();
        for (Map.Entry<String, RelacijaStanje> e : konacnaStanjaZaJoin.entrySet()) {
            Set<String> s = new HashSet<>(); s.add(e.getKey());
            aktivniPlanovi.put(s, e.getValue());
        }

        List<JoinUslov> preostaliUslovi = new ArrayList<>(sviJoinUslovi);
        int joinIndex = 1;

        while (aktivniPlanovi.size() > 1) {
            Set<String> najboljaLeva = null, najboljaDesna = null;
            JoinUslov najboljiUslov = null;
            Cena najboljaCena = null;
            String najboljiAlgoritam = "";

            for (JoinUslov ju : preostaliUslovi) {
                Set<String> levi = null, desni = null;
                for (Set<String> k : aktivniPlanovi.keySet()) {
                    if (k.contains(ju.alijasL)) levi = k;
                    if (k.contains(ju.alijasR)) desni = k;
                }
                if (levi == null || desni == null || levi.equals(desni)) continue;

                RelacijaStanje sL = aktivniPlanovi.get(levi), sD = aktivniPlanovi.get(desni);
                Cena c1 = evaluirajSpajanje(sL, sD, ju.colL, ju.colR, aliasToTableMap, M);
                Cena c2 = evaluirajSpajanje(sD, sL, ju.colR, ju.colL, aliasToTableMap, M);
                Cena lokalno = c1.bt <= c2.bt ? c1 : c2;
                String alg = c1.bt <= c2.bt ? c1.algoritam : c2.algoritam + " [obrnut redosled]";

                if (najboljaCena == null || lokalno.bt < najboljaCena.bt) {
                    najboljaCena = lokalno; najboljiAlgoritam = alg;
                    najboljaLeva = levi; najboljaDesna = desni; najboljiUslov = ju;
                }
            }

            if (najboljiUslov == null) {
                Set<String> bestA = null, bestB = null; long bestCost = Long.MAX_VALUE;
                List<Set<String>> kljucevi = new ArrayList<>(aktivniPlanovi.keySet());
                for (int x = 0; x < kljucevi.size(); x++) {
                    for (int y = x + 1; y < kljucevi.size(); y++) {
                        RelacijaStanje a = aktivniPlanovi.get(kljucevi.get(x));
                        RelacijaStanje b = aktivniPlanovi.get(kljucevi.get(y));
                        long cost = (long) a.blockCount * b.blockCount + a.blockCount;
                        if (cost < bestCost) { bestCost = cost; bestA = kljucevi.get(x); bestB = kljucevi.get(y); }
                    }
                }
                najboljaLeva = bestA; najboljaDesna = bestB;
                RelacijaStanje l = aktivniPlanovi.get(najboljaLeva), d = aktivniPlanovi.get(najboljaDesna);
                
                long dpBT = (long) l.blockCount * d.blockCount + l.blockCount;
                najboljaCena = new Cena(dpBT, 1 + l.blockCount, "DEKARTOV PROIZVOD");
                najboljiAlgoritam = najboljaCena.algoritam;
            } else {
                preostaliUslovi.remove(najboljiUslov);
            }

            RelacijaStanje sLevo = aktivniPlanovi.remove(najboljaLeva);
            RelacijaStanje sDesno = aktivniPlanovi.remove(najboljaDesna);

            System.out.println("\n [Join #" + joinIndex + "] " + najboljaLeva + " |X| " + najboljaDesna);
            System.out.println("   ==> [ODLUKA]: " + najboljiAlgoritam);
            System.out.println("   => Cena: " + najboljaCena.bt + " BT, " + najboljaCena.sc + " SC.");

            totalBT += najboljaCena.bt;
            totalSC += najboljaCena.sc;

            double naknadnaSelektivnost = 1.0;
            for (String alijas : uni(najboljaLeva, najboljaDesna)) {
                if (odlozeniUslovi.containsKey(alijas)) {
                    Table t = aliasToTableMap.get(alijas);
                    for (Expression e : odlozeniUslovi.get(alijas)) {
                        Attribute attr = t.getAttribute(ColumnExtractor.getColumns(e).get(0).getColumnName());
                        naknadnaSelektivnost *= selektivnostUslova(e, attr);
                    }
                    odlozeniUslovi.remove(alijas);
                }
            }

            int vLevo = 1, vDesno = 1;
            if (najboljiUslov != null) {
                Table tLeva = aliasToTableMap.get(sLevo.alijasKljuc);
                Table tDesna = aliasToTableMap.get(sDesno.alijasKljuc);
                if (tLeva != null) {
                    Attribute a = tLeva.getAttribute(najboljiUslov.colL.getColumnName());
                    if (a != null && a.distinctValues > 0) vLevo = a.distinctValues;
                }
                if (tDesna != null) {
                    Attribute a = tDesna.getAttribute(najboljiUslov.colR.getColumnName());
                    if (a != null && a.distinctValues > 0) vDesno = a.distinctValues;
                }
            }
            int maxV = Math.max(vLevo, vDesno);

            int noviRedovi = Math.max(1, (int) Math.ceil(((double) sLevo.rowCount * sDesno.rowCount * naknadnaSelektivnost) / maxV));
            
            // FAKTOR BLOKIRANJA (popravljeno na inverznu formulu za širinu sloga)
            int noviRowsPerBlock = Math.max(1, (sLevo.rowsPerBlock * sDesno.rowsPerBlock) / (sLevo.rowsPerBlock + sDesno.rowsPerBlock));
            int noviBlokovi = Math.max(1, (int) Math.ceil((double) noviRedovi / noviRowsPerBlock));

            boolean inMem = (noviBlokovi <= M);

            // obračun troška upisa na disk ukoliko međutabela ne staje u radnu memoriju
            if (!inMem) {
//                long upisBT = 2L*noviBlokovi;
//                long upisSC = 2;
                
                long upisBT = noviBlokovi;
                long upisSC = 1;
                
                System.out.println("   [Vracanje na disk] Medjurezultat je preveliki za memorijski bafer (" + noviBlokovi + " blokova > " + M + " M).");
                System.out.println("                Dodatni trosak upisa na disk: +" + upisBT + " BT, +" + upisSC + " SC.");
                
                totalBT += upisBT;
                totalSC += upisSC;
            } else {
                System.out.println("   [Memory Optim] Medjurezultat kompletan staje u memoriju (" + noviBlokovi + " blokova <= " + M + " M). Upis na disk = 0.");
            }

            RelacijaStanje novi = new RelacijaStanje("Medjurezultat_" + joinIndex, noviRedovi, noviBlokovi, noviRowsPerBlock, sLevo.alijasKljuc, false, inMem);
            Set<String> unija = uni(najboljaLeva, najboljaDesna);
            aktivniPlanovi.put(unija, novi);
            joinIndex++;
        }

        if (konacnaStanjaZaJoin.size() == 1 && sviJoinUslovi.isEmpty()) {
            RelacijaStanje jedina = konacnaStanjaZaJoin.values().iterator().next();
            List<Expression> uslovi = lokalneSelekcijePoAlijasu.get(jedina.alijasKljuc);
            if (uslovi == null || uslovi.isEmpty()) {
                totalBT = jedina.blockCount;
                totalSC = 1;
            }else {
                // Slučaj 2: Imamo selekciju (Pushdown je odrađen)
                // Osnovna cena je već u totalBT (fiksniTrosakSelekcijaBT)
                
                // Dodajemo trošak upisa na disk ako međurezultat ne staje u memorijski bafer (M)
                if (jedina.blockCount > M) {
                    System.out.println("\n [Vracanje na disk] Rezultat selekcije (" + jedina.blockCount + " blokova) ne staje u memoriju (" + M + " M).");
                    System.out.println("                    Dodatni trosak upisa na disk: +" + jedina.blockCount + " BT, +1 SC.");
                    totalBT += jedina.blockCount;
                    totalSC += 1;
//                	long dodatniBT = 2L * jedina.blockCount; // upis + naknadno čitanje
//                    System.out.println("\n [Vracanje na disk] Rezultat selekcije (" + jedina.blockCount + " blokova) ne staje u memoriju (" + M + " M).");
//                    System.out.println("                    Dodatni trosak (upis + citanje): +" + dodatniBT + " BT, +2 SC.");
//                    totalBT += dodatniBT;
//                    totalSC += 2;
                } else {
                    System.out.println("\n [Memory Optim] Rezultat selekcije staje u memoriju. Upis na disk = 0.");
                }
            }
        }

        // ORDER BY
        boolean imaOrderBy = queryBody.getOrderByElements() != null && !queryBody.getOrderByElements().isEmpty();
        if (imaOrderBy) {
            RelacijaStanje finalna = aktivniPlanovi.isEmpty()
                    ? konacnaStanjaZaJoin.values().iterator().next()
                    : aktivniPlanovi.values().iterator().next();

            Column orderCol = ColumnExtractor.getColumns((Expression) queryBody.getOrderByElements().get(0).getExpression()).isEmpty()
                    ? null : ColumnExtractor.getColumns((Expression) queryBody.getOrderByElements().get(0).getExpression()).get(0);

            boolean vecSortirano = false;
            if (orderCol != null && finalna.indeksDostupan) {
                String orderAlijas = (orderCol.getTable() != null) ? orderCol.getTable().getName().toLowerCase() : defaultTableKey;
                Table t = aliasToTableMap.get(orderAlijas);
                vecSortirano = jeSortiranaPoAtributu(t, orderCol);
            }

            if (!vecSortirano) {
                long sortBT;
                long sortSC;
                
                if (finalna.medjurezultatUMemoriji) {
                    sortBT = 0;
                    sortSC = 0;
                    System.out.println("\n [ORDER BY] Sortiranje finalnog rezultata (" + finalna.blockCount + " blokova) izvrseno kompletno u MEMORIJI: 0 BT, 0 SC.");
                } else {
                    sortBT = eksternoSortiranjeBT(finalna.blockCount, M);
                    sortSC = eksternoSortiranjeSC(finalna.blockCount, M);
                    System.out.println("\n [ORDER BY] Eksterno sortiranje finalnog rezultata (" + finalna.blockCount + " blokova): " + sortBT + " BT, " + sortSC + " SC.");
                }
                totalBT += sortBT;
                totalSC += sortSC;
            } else {
                System.out.println("\n [ORDER BY] Rezultat je vec fizicki sortiran po trazenom atributu - besplatno.");
            }
        }

        System.out.println("\n------------------------------------------------------");
        System.out.println(" KONACNI REZULTAT:");
        System.out.println(" -> UKUPNO BLOK TRANSFERA (BT): " + totalBT);
        System.out.println(" -> UKUPNO SEEK COST (SC):      " + totalSC);
        System.out.println("\n-------------------------------------------------------");
    }

    private static Set<String> uni(Set<String> a, Set<String> b) {
        Set<String> r = new HashSet<>(a); r.addAll(b); return r;
    }

    private static boolean proceniDaLiJeOdlaganjeIsplativije(RelacijaStanje stanjeA, RelacijaStanje stanjeB, Cena cenaA,
            String alijas, List<JoinUslov> sviJoinUslovi, Map<String, Table> aliasToTableMap,
            Map<String, RelacijaStanje> baznaStanja, Map<String, RelacijaStanje> konacnaStanjaZaJoin, int M) {

        JoinUslov najblizi = null;
        for (JoinUslov ju : sviJoinUslovi) {
            if (ju.alijasL.equals(alijas) || ju.alijasR.equals(alijas)) { najblizi = ju; break; }
        }
        if (najblizi == null) return false;

        String drugiAlijas = najblizi.alijasL.equals(alijas) ? najblizi.alijasR : najblizi.alijasL;
        
        // POPRAVKA: Prvo gledamo da li za drugu tabelu vec imamo formirano konacno stanje, ako ne, uzimamo bazno
        RelacijaStanje drugi = konacnaStanjaZaJoin.containsKey(drugiAlijas) ? konacnaStanjaZaJoin.get(drugiAlijas) : baznaStanja.get(drugiAlijas);
        if (drugi == null) return false;

        Column kolAlijas = najblizi.alijasL.equals(alijas) ? najblizi.colL : najblizi.colR;
        Column kolDrugi = najblizi.alijasL.equals(alijas) ? najblizi.colR : najblizi.colL;

        Cena zaA = evaluirajSpajanje(stanjeA, drugi, kolAlijas, kolDrugi, aliasToTableMap, M);
        Cena zaB = evaluirajSpajanje(stanjeB, drugi, kolAlijas, kolDrugi, aliasToTableMap, M);

        long ukupnoA = cenaA.bt + zaA.bt;
        long ukupnoB = zaB.bt;

        return ukupnoB < ukupnoA;
    }

    private static double selektivnostUslova(Expression e, Attribute a) {
        if (a == null || a.distinctValues <= 0) return 1.0;
        if (jeOpseg(e)) return SELEKTIVNOST_OPSEGA_BEZ_STATISTIKE;
        return a.unique ? (1.0 / Math.max(1, a.distinctValues)) : (1.0 / a.distinctValues);
    }

    private static boolean jeOpseg(Expression e) {
        return e instanceof GreaterThan || e instanceof GreaterThanEquals
                || e instanceof MinorThan || e instanceof MinorThanEquals;
    }

    private static double kombinovanaSelektivnost(Table tabela, List<Expression> uslovi) {
        double sel = 1.0;
        for (Expression e : uslovi) {
            Column c = ColumnExtractor.getColumns(e).get(0);
            Attribute a = tabela.getAttribute(c.getColumnName());
            sel *= selektivnostUslova(e, a);
        }
        return sel;
    }

    private static Cena evaluirajUslove(RelacijaStanje stanje, Table tabela, List<Expression> uslovi) {
    	boolean tabelaImaNekiAdekvatniIndeks = false;
        if (uslovi.size() > 1) {
            List<String> imena = new ArrayList<>();
            for (Expression e : uslovi) imena.add(ColumnExtractor.getColumns(e).get(0).getColumnName());
            Index kompozitni = tabela.getBestIndexForComposite(imena);
            if (kompozitni != null) {
            	tabelaImaNekiAdekvatniIndeks = true;
                double sel = kombinovanaSelektivnost(tabela, uslovi);
                int procenjeniRedovi = Math.max(1, (int) Math.ceil(stanje.rowCount * sel));
                boolean sveJedinstvene = true;
                for (Expression e : uslovi) {
                    Attribute a = tabela.getAttribute(ColumnExtractor.getColumns(e).get(0).getColumnName());
                    if (a == null || !a.unique) sveJedinstvene = false;
                }
                int[] c = indexAccessCost(kompozitni, stanje.rowCount, stanje.blockCount, stanje.rowsPerBlock, sveJedinstvene, procenjeniRedovi);
                if (c[0] < stanje.blockCount) {
                    return new Cena(c[0], c[1], "A8 - kompozitni indeks [" + kompozitni.name + "]",true);
                }
            }
        }

        Cena najjeftiniji = new Cena(stanje.blockCount, 1, "A1 - linearna pretraga");
        for (Expression e : uslovi) {
            Column c = ColumnExtractor.getColumns(e).get(0);
            Attribute a = tabela.getAttribute(c.getColumnName());
            if (a == null) continue;
            
            if(tabela.getBestIndexFor(a.name)!=null) {
            	tabelaImaNekiAdekvatniIndeks = true;
            }
            Cena cena = evaluirajJedanUslov(stanje, tabela, a, e);
            if (cena.bt < najjeftiniji.bt) najjeftiniji = cena;
        }
        
        if(najjeftiniji.algoritam.contains("linearna")) {
        	najjeftiniji.indeksRazmatran = tabelaImaNekiAdekvatniIndeks;
        }else {
        	najjeftiniji.indeksRazmatran = true;
        }
        
        return najjeftiniji;
    }

    private static Cena evaluirajJedanUslov(RelacijaStanje stanje, Table tabela, Attribute attr, Expression uslov) {
        boolean jednakost = !jeOpseg(uslov);
        int scanBT = stanje.blockCount;
        int scanSC = 1;
        
        if (attr.unique && jednakost) {
            scanBT = Math.max(1, (int) Math.ceil((double) stanje.blockCount / 2));
        }
        Cena scanCena = new Cena(scanBT, scanSC, "A1 - linearna pretraga");

        Index indeks = tabela.getBestIndexFor(attr.name);
        if (indeks == null) return scanCena;

        double selektivnost = selektivnostUslova(uslov, attr);
        int procenjeniRedovi = Math.max(1, (int) Math.ceil(stanje.rowCount * selektivnost));

        if (jednakost && attr.unique) {
            procenjeniRedovi = 1;
        }

        if (!jednakost && "HASH".equals(indeks.type)) return scanCena;

        int[] c = indexAccessCost(indeks, stanje.rowCount, stanje.blockCount, stanje.rowsPerBlock, attr.unique && jednakost, procenjeniRedovi);
        if (c[0] >= scanBT) return scanCena;

        String naziv = (jednakost ? "A2/A3/A4" : "A5/A6") + " - indeksni pristup [" + indeks.name + "]";
        return new Cena(c[0], c[1], naziv);
    }

    private static int[] indexAccessCost(Index idx, int totalRows, int totalBlocks, int rowsPerBlock, boolean unique, int estimatedRows) {
        if ("B_PLUS_TREE".equals(idx.type)) {
            int h = idx.treeHeight;
            if (unique) return new int[]{h + 1, h + 1};
            if (idx.clustered) {
                int b = Math.max(1, (int) Math.ceil((double) estimatedRows / rowsPerBlock));
                return new int[]{h + b, h + 1};
            }
            int indexLeafBlocks = Math.max(1, (int) Math.ceil((double) estimatedRows / (rowsPerBlock * 4)));
            return new int[]{h + indexLeafBlocks + estimatedRows, h + 1 + estimatedRows};
        } else if ("HASH".equals(idx.type)) {
            if (unique) return new int[]{2, 2};
            return new int[]{1 + estimatedRows, 1 + estimatedRows};
        }
        return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};
    }

    private static Cena evaluirajSpajanje(RelacijaStanje spoljna, RelacijaStanje unutrasnja, Column colSpoljna, Column colUnutrasnja,
            Map<String, Table> aliasToTableMap, int M) {
        int bb = Math.max(1, M - 2);
        long ucitavanjeSpoljneBT = spoljna.blockCount;

        //  BNLJ
        int prolaziBNLJ = (int) Math.ceil((double) spoljna.blockCount / bb);
        long bnljBT = (long) prolaziBNLJ * unutrasnja.blockCount + ucitavanjeSpoljneBT;
        long bnljSC = 2L * prolaziBNLJ;
        Cena best = new Cena(bnljBT, bnljSC, "BLOCK NESTED-LOOP JOIN (BNLJ)");

        // INLJ (popravljen Seek Cost + ZAŠTITA ZA MEĐUREZULTATE) 
        // ako je unutrašnja relacija privremeni međurezultat, ona nema indeks! INLJ se preskače.
        if (unutrasnja.indeksDostupan && !unutrasnja.medjurezultatUMemoriji && !unutrasnja.naziv.startsWith("Medjurezultat")) {
            Table tUnutrasnja = aliasToTableMap.get(unutrasnja.alijasKljuc);
            if (tUnutrasnja != null) {
                Attribute a = tUnutrasnja.getAttribute(colUnutrasnja.getColumnName());
                Index idx = (a != null) ? tUnutrasnja.getBestIndexFor(a.name) : null;
                if (idx != null) {
                    int predvidjenoZapisaPoPogotku = Math.max(1, (int) Math.ceil((double) unutrasnja.rowCount / Math.max(1, a.distinctValues)));
                    if (a.unique) predvidjenoZapisaPoPogotku = 1;

                    int[] c = indexAccessCost(idx, unutrasnja.rowCount, unutrasnja.blockCount, unutrasnja.rowsPerBlock, a.unique, predvidjenoZapisaPoPogotku);
                    
                    long inljBT = ucitavanjeSpoljneBT + (long) spoljna.rowCount * c[0];
                    long inljSC = 1L + (long) spoljna.rowCount * c[1]; // 1 SC za sekvencijalnu spoljnu
                    if (inljBT < best.bt) {
                        best = new Cena(inljBT, inljSC, "INDEX NESTED-LOOP JOIN (INLJ) [indeks: " + idx.name + "]");
                    }
                }
            }
        }

        // SORT-MERGE JOIN (popravljen proračun faza i SC)
        Table tSpoljna = aliasToTableMap.get(spoljna.alijasKljuc);
        Table tUnutrasnja = aliasToTableMap.get(unutrasnja.alijasKljuc);
        boolean spoljnaSortirana = spoljna.indeksDostupan && jeSortiranaPoAtributu(tSpoljna, colSpoljna);
        boolean unutrasnjaSortirana = unutrasnja.indeksDostupan && jeSortiranaPoAtributu(tUnutrasnja, colUnutrasnja);

        long sortSpoljnaBT = spoljnaSortirana ? 0 : eksternoSortiranjeBT(spoljna.blockCount, M);
        long sortUnutrasnjaBT = unutrasnjaSortirana ? 0 : eksternoSortiranjeBT(unutrasnja.blockCount, M);
        long sortSpoljnaSC = spoljnaSortirana ? 0 : eksternoSortiranjeSC(spoljna.blockCount, M);
        long sortUnutrasnjaSC = unutrasnjaSortirana ? 0 : eksternoSortiranjeSC(unutrasnja.blockCount, M);
        
        long mergeBT = sortSpoljnaBT + sortUnutrasnjaBT + ucitavanjeSpoljneBT + unutrasnja.blockCount;
        long mergeSC = sortSpoljnaSC + sortUnutrasnjaSC + 2; // +2 za finalno spajanje dve niti
        if (mergeBT < best.bt) best = new Cena(mergeBT, mergeSC, "SORT-MERGE JOIN (SMJ)");

        // HASH JOIN 
        Cena hash = hashJoinCena(spoljna.blockCount, unutrasnja.blockCount, M);
        if (hash.bt < best.bt) best = hash;

        return best;
    }

//    private static Cena hashJoinCena(int b_r, int b_s_ulaz, int M) {
//        int b_s = Math.min(b_r, b_s_ulaz);
//        int b_r_stvarno = Math.max(b_r, b_s_ulaz);
//
//        long hashBT, hashSC;
//        if (b_s < M) {
//            hashBT = b_r_stvarno + b_s;
//            hashSC = 2;
//        } else {
//            // opšta  3*(Br + Bs) formula za particionisanje
//            hashBT = 3L * (b_r_stvarno + b_s);
//            int n_h = (int) Math.ceil((double) b_s / M);
//            int b_b = Math.max(1, M / (n_h + 1));
//            hashSC = 2L * ((long) Math.ceil((double) b_r_stvarno / b_b) + (long) Math.ceil((double) b_s / b_b));
//        }
//        return new Cena(hashBT, hashSC, "HASH JOIN");
//    }

    private static Cena hashJoinCena(int b_r, int b_s_ulaz, int M) {
        int b_s = Math.min(b_r, b_s_ulaz);
        int b_r_stvarno = Math.max(b_r, b_s_ulaz);

        long hashBT, hashSC;
        if (b_s < M) {
            // Poslednja stavka sa slajda 26: 
            // Cela gradivna relacija stane u memoriju -> b_r + b_s i dve pretrage
            hashBT = b_r_stvarno + b_s;
            hashSC = 2; 
        } else {
            // Formule sa slajda 24 i 26 za slučaj kada rekurzivno particionisanje nije potrebno:
            
            // 1. n_h (broj particija) = ceil(b_s / M) * fudge_factor (f = 1.2)
            double fudgeFactor = 1.2;
            int n_h = (int) Math.ceil(((double) b_s / M) * fudgeFactor);
            
            // Osiguravamo da n_h ima smisla (mora biti bar 2 particije ako ne staje u memoriju)
            if (n_h < 2) n_h = 2;

            // 2. b_b (veličina ulazno/izlaznih bafera) = M / (n_h + 1)
            // Uzimamo celobrojno deljenje (floor) po formuli sa slajda 26: M = (n_h + 1) * b_b
            int b_b = M / (n_h + 1);
            if (b_b < 1) b_b = 1; // Bafer mora imati bar 1 blok za rad

            // 3. BT = 3*(b_r + b_s) + 4 * n_h  (slajd 26)
            hashBT = 3L * (b_r_stvarno + b_s) + 4L * n_h;
            
            // 4. SC = 2 * ( ceil(b_r / b_b) + ceil(b_s / b_b) ) + 2 * n_h  (slajd 26)
            long gornjiR = (long) Math.ceil((double) b_r_stvarno / b_b);
            long gornjiS = (long) Math.ceil((double) b_s / b_b);
            hashSC = 2L * (gornjiR + gornjiS) + 2L * n_h;
        }
        return new Cena(hashBT, hashSC, "HASH JOIN");
    }
    private static boolean jeSortiranaPoAtributu(Table tabela, Column kolona) {
        if (tabela == null || kolona == null) return false;
        Attribute attr = tabela.getAttribute(kolona.getColumnName());
        if (attr == null) return false;
        Index idx = tabela.getBestIndexFor(attr.name);
        return idx != null && "B_PLUS_TREE".equals(idx.type) && idx.clustered;
    }

    private static long eksternoSortiranjeBT(int b, int M) {
        if (b <= M) return 2L * b;
        return (long) b * (2L * brojFazaSortiranja(b, M) + 1);
    }

    private static long eksternoSortiranjeSC(int b, int M) {
        if (b <= M) return 2L;
        int inicijalniNizovi = (int) Math.ceil((double) b / M);
        long fazaSC = 2L * inicijalniNizovi;
        return fazaSC * (brojFazaSortiranja(b, M) + 1);
    }

    private static int brojFazaSortiranja(int b, int M) {
        int inicijalniNizovi = (int) Math.ceil((double) b / M);
        if (inicijalniNizovi <= 1) return 0;
        int delilac = Math.max(2, M - 1);
        return Math.max(1, (int) Math.ceil(Math.log(inicijalniNizovi) / Math.log(delilac)));
    }

    private static void razbijKonjunkciju(Expression expr, List<Expression> lista) {
        if (expr instanceof AndExpression) {
            AndExpression and = (AndExpression) expr;
            razbijKonjunkciju(and.getLeftExpression(), lista);
            razbijKonjunkciju(and.getRightExpression(), lista);
        } else if (expr != null) {
            lista.add(expr);
        }
    }
}