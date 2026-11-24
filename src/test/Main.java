package test;

import java.io.File;
import java.util.List;
import java.util.Scanner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

import schema.Schema;
import schema.Table;
import validator.SQLValidator;
import optimizer.QueryOptimizer;

public class Main {
	public static void main(String[] args) {
		try {
			// inicijalizacija  i učitavanje JSON šeme baze podataka
			ObjectMapper mapper = new ObjectMapper();

			//učitavam ceo JSON kao generičko stablo (JsonNode)
			JsonNode rootNode = mapper.readTree(new File("resourses/primer_ulaza.json"));

			// pravimo objekat  klase Schema
			Schema schema = new Schema();

			// izvlačim "bufferBlocks" koji je na korenu JSON-a
			schema.bufferBlocks = rootNode.get("bufferBlocks").asInt();

			// ulazim u objekat "schema" i iz njega čitamo listu tabela
			JsonNode schemaNode = rootNode.get("schema");
			JsonNode tablesNode = schemaNode.get("tables");

			// pretvaramo taj deo JSON-a u List<Table> i dodeljujemo tvojoj klasi Schema
			schema.tables = mapper.convertValue(tablesNode, new TypeReference<List<Table>>() {
			});

			// Ispis uspešnog učitavanja kataloga baze podataka
			System.out.println("=========================================================");
			System.out.println("      SISTEM ZA PROCENU I OPTIMIZACIJU UPITA UKLJUČEN   ");
			System.out.println("=========================================================");
			System.out.println("Uspesno ucitan zvanični JSON!");
			System.out.println("Velicina bafera u RAM memoriji (M): " + schema.bufferBlocks + " blokova.");
			System.out.println("Broj ucitanih tabela iz seme: " + schema.tables.size());
			System.out.println("=========================================================");

			// pokretanje interaktivne konzole za unos SQL upita

			// citanje iz fajla

			File upitiFajl = new File("resourses/upiti.txt");

			if (!upitiFajl.exists()) {
				System.out.println("Kriticna greska: fajl sa upitima " + upitiFajl.getPath() + " ne postoji!");
			}
			Scanner scanner = new Scanner(upitiFajl);
			int brojacUpita = 1;

			while (scanner.hasNext()) {
				String sql = scanner.nextLine().trim();

				if (sql.isEmpty() || sql.startsWith("--")) {
					continue;
				}

				System.out.println("\n#########################################################");
				System.out.println("   OBRADA UPITA #" + brojacUpita);
				System.out.println("#########################################################");
				System.out.println("Tekst upita: " + sql);

				try {
					// SINTAKSNA ANALIZA (PARSIRANJE) 
					Statement statement = CCJSqlParserUtil.parse(sql);

					if (!(statement instanceof Select)) {
						System.out.println("SINTAKSNA GRESKA: Sistem podrzava samo SELECT upite!");
						brojacUpita++;
						continue;
					}

					Select selectStatement = (Select) statement;
					@SuppressWarnings("deprecation")
					PlainSelect queryBody = (PlainSelect) selectStatement.getSelectBody();

					// SEMANTIČKA ANALIZA (VALIDACIJA) 
					boolean isValid = SQLValidator.validate(queryBody, schema);

					if (isValid) {
						System.out.println("\n>>> Upit je SINTAKSNO i SEMANTIČKI ispravan! <<<");

						//HEURISTIČKA OPTIMIZACIJA I PRORAČUN CENA 
						QueryOptimizer.optimize(queryBody, schema);
					}

				} catch (Exception e) {
					System.out.println("SINTAKSNA GRESKA: " + e.getMessage());
				}

				brojacUpita++;
			}

			scanner.close();
			System.out.println("\n=========================================================");
			System.out.println(" SVI UPITI IZ FAJLA SU USPJEŠNO OBRAĐENI.");
			System.out.println("=========================================================");

		} catch (Exception e) {
			System.out.println("KRITIČNA GRESKA PRILIKOM POKRETANJA SISTEMA:");
			e.printStackTrace();
		}

	}

}