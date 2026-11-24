package validator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.FromItem;
import schema.Schema;
import schema.Table;

public class SQLValidator {
    
    public static boolean validate(PlainSelect queryBody, Schema schema) {
        
        // Mapa koja će pamtiti koji alijas (ili ime) odgovara kojoj stvarnoj tabeli iz šeme
        // Ključ je alijas (sve malim slovima), a vrednost je stvarna Table klasa
        Map<String, Table> aliasToTableMap = new HashMap<>();
        
        // 1. Provera i obrada glavne tabele iz FROM dela
        FromItem fromItem = queryBody.getFromItem();
        if (fromItem == null) {
            System.out.println("Semanticka greska: FROM klauzula je prazna!");
            return false;
        }

        String mainRealName = "";
        String mainAlias = "";

        // JSQLParser ima specifičnu klasu za tabelu, proveravamo bezbedno preko punog imena
        if (fromItem instanceof net.sf.jsqlparser.schema.Table) {
            net.sf.jsqlparser.schema.Table jsqlTable = (net.sf.jsqlparser.schema.Table) fromItem;
            mainRealName = jsqlTable.getName(); // Ovo vraća čisto ime, npr. "Student"
            if (jsqlTable.getAlias() != null) {
                mainAlias = jsqlTable.getAlias().getName(); // Ovo vraća alijas, npr. "s"
            }
        } else {
            mainRealName = fromItem.toString();
        }
        
        Table mainTable = schema.getTable(mainRealName);
        if (mainTable == null) {
            System.out.println("Semanticka greska: Tabela '" + mainRealName + "' ne postoji u semi!");
            return false;
        }
        
        // Beležimo u mapu: tabela je dostupna i preko svog pravog imena i preko alijasa (ako postoji)
        aliasToTableMap.put(mainRealName.toLowerCase(), mainTable);
        if (!mainAlias.isEmpty()) {
            aliasToTableMap.put(mainAlias.toLowerCase(), mainTable);
        }
        
        // Podrazumevani ključ: ako kolona nema napisanu tabelu ispred sebe (npr. samo "smer = 'RI'"),
        // ona se automatski odnosi na prvu (glavnu) tabelu iz FROM dela.
        String defaultTableKey = !mainAlias.isEmpty() ? mainAlias.toLowerCase() : mainRealName.toLowerCase();
        
        // 2. Provera JOIN tabela ako ih ima
        if (queryBody.getJoins() != null) {
            for (Join join : queryBody.getJoins()) {
                FromItem joinItem = join.getRightItem();
                String joinRealName = "";
                String joinAlias = "";
                
                if (joinItem instanceof net.sf.jsqlparser.schema.Table) {
                    net.sf.jsqlparser.schema.Table jsqlJoinTable = (net.sf.jsqlparser.schema.Table) joinItem;
                    joinRealName = jsqlJoinTable.getName();
                    if (jsqlJoinTable.getAlias() != null) {
                        joinAlias = jsqlJoinTable.getAlias().getName();
                    }
                } else {
                    joinRealName = joinItem.toString();
                }
                
                Table joinTable = schema.getTable(joinRealName);
                if (joinTable == null) {
                    System.out.println("Semanticka greska: Tabela u JOIN-u '" + joinRealName + "' ne postoji u semi!");
                    return false;
                }
                
                // Beležimo i nju u mapu alijasa
                aliasToTableMap.put(joinRealName.toLowerCase(), joinTable);
                if (!joinAlias.isEmpty()) {
                    aliasToTableMap.put(joinAlias.toLowerCase(), joinTable);
                }
            }
        }
        
        // 3. Razrešavanje i provera WHERE klauzule
        if (queryBody.getWhere() != null) {
            List<Column> whereColumns = ColumnExtractor.getColumns(queryBody.getWhere());
            
            for (Column col : whereColumns) {
                // Ako kolona ima tabelu/alijas ispred (npr. s.indeks), uzmi to ime. Ako nema, uzmi default tabelu.
                String tableOrAliasName = (col.getTable() != null) ? col.getTable().getName().toLowerCase() : defaultTableKey;
                String attrName = col.getColumnName();
                
                // Sada bezbedno tražimo preko mape alijasa, a ne direktno iz šeme!
                Table t = aliasToTableMap.get(tableOrAliasName);
                
                if (t == null) {
                    System.out.println("Semanticka greska: Prefiks/Alijas '" + tableOrAliasName + "' upotrebljen u WHERE uslovu ne odgovara nijednoj tabeli!");
                    return false;
                }
                
                if (t.getAttribute(attrName) == null) {
                    System.out.println("Semanticka greska: Atribut '" + attrName + "' ne postoji u tabeli '" + t.name + "' (WHERE uslov)!");
                    return false;
                }
            }
        }
        
        // 4. Provera SELECT stavki (projektovanih atributa)
        for (SelectItem<?> item : queryBody.getSelectItems()) {
            if (!item.toString().equals("*")) {
                List<Column> selectColumns = ColumnExtractor.getColumns(item.getExpression());
                
                for (Column col : selectColumns) {
                    String tableOrAliasName = (col.getTable() != null) ? col.getTable().getName().toLowerCase() : defaultTableKey;
                    String attrName = col.getColumnName();
                    
                    // Ponovo tražimo preko mape alijasa
                    Table t = aliasToTableMap.get(tableOrAliasName);
                    
                    if (t == null) {
                        System.out.println("Semanticka greska: Prefiks/Alijas '" + tableOrAliasName + "' upotrebljen u SELECT listi ne odgovara nijednoj tabeli!");
                        return false;
                    }
                    
                    if (t.getAttribute(attrName) == null) {
                        System.out.println("Semanticka greska: Atribut '" + attrName + "' ne postoji u tabeli '" + t.name + "' (SELECT lista)!");
                        return false;
                    }
                }
            }
        }
        
        return true; // Sve semantičke provere uspešno prošle
    }
}