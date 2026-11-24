package schema;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonRootName;


public class Schema {
	
	public int bufferBlocks;
	public List<Table> tables;
	
	public Table getTable(String name) {
		for(Table t : tables) {
			if(t.name.equalsIgnoreCase(name)) return t;
		}
		return null;
	}

}
