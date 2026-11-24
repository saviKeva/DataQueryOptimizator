package validator;

import java.util.ArrayList;
import java.util.List;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;

public class ColumnExtractor extends ExpressionVisitorAdapter {

	private final List<Column> extractedColumns = new ArrayList<>();
	
	@Override
	public void visit(Column column) {
		extractedColumns.add(column);
		super.visit(column);
	}
	
	
	public static List<Column> getColumns(Expression expr){
		ColumnExtractor extractor = new ColumnExtractor();
		if(expr!=null) {
			expr.accept(extractor);
		}
		
		return extractor.extractedColumns;
	}
}
