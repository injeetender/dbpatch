package org.jsoftware.impl;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.jsoftware.config.ConfigurationEntry;
import org.jsoftware.config.Patch;
import org.jsoftware.impl.statements.CommentPatchStatement;
import org.jsoftware.impl.statements.DisallowedSqlPatchStatement;
import org.jsoftware.impl.statements.SqlPatchStatement;
import org.jsoftware.log.LogFactory;
import org.jsoftware.simpleparser.SimpleParser;
import org.jsoftware.simpleparser.SimpleParserCallback;
import org.jsoftware.simpleparser.SimpleParserCallbackContext;


public class DefaultPatchParser extends SimpleParser implements PatchParser {
	private enum PSTATE { sql, comment_line, comment_block, sql_block, inside_singlequot, inside_doublequot };
	private static final String DEFAULT_DELIMITER = ";";
	private String delimiter = DEFAULT_DELIMITER;
	private Collection<String> disallowed;
	
	public DefaultPatchParser() {
		super("--", "//", DEFAULT_DELIMITER, "\n", "/\\*", "\\*/", "\"", "'");
		disallowed = new HashSet<String>();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/dbpatch-disallowedStatements.txt")));
			String s;
			while((s = br.readLine()) != null) {
				s = s.trim();
				if (s.length() > 0) {
					disallowed.add(s);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Can not load disallowed statements.", e);
		} finally {
			CloseUtil.close(br);
		}
		LogFactory.getInstance().debug("Disallowed statements " + disallowed);
	}
	
	public ParseResult parse(InputStream inputStream, ConfigurationEntry ce) throws IOException {
		Charset charset = ce == null ? Charset.defaultCharset() : ce.getPatchEncoding();
		LogFactory.getInstance().debug("Patch encoding set to " + charset.displayName() + (ce == null ? " (system default)" : ""));
		BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, charset));
		StringBuilder sb = new StringBuilder();
		String l;
		while ((l = br.readLine()) != null) {
			sb.append(l).append('\n');
		}
		br.close();
		final List<PatchStatement> statements = new LinkedList<PatchStatement>();
		parse(sb.toString(), new SqlParserCallback(statements));
		return new ParseResult() {
			public int totalCount() {
				return statements.size();
			}
			public List<PatchStatement> getStatements() {
				return Collections.unmodifiableList(statements);
			}
			public int executableCount() {
				int c = 0;
				for(PatchStatement ps : statements) {
					if (ps.isExecutable()) c++;
				}
				return c;
			}
		};
	}
	
	public ParseResult parse(Patch p, ConfigurationEntry ce) throws IOException {
		ParseResult pr = parse(new FileInputStream(p.getFile()), ce);
		p.setStatementCount(pr.executableCount());
		return pr;
	}

	
	class SqlParserCallback implements SimpleParserCallback {
		private PSTATE current = PSTATE.sql;
		private Collection<PatchStatement> statements;
		private StringBuilder buf;
		
		public SqlParserCallback(List<PatchStatement> statements) {
			this.statements = statements;
			buf = new StringBuilder();
		}
		
		public void documentStarts() {
		}
		
		public void tokenFound(SimpleParserCallbackContext ctx, String token) {
			String text = ctx.getTextBefore();
			buf.append(text);
			if (current == PSTATE.sql_block) {
				if (! token.equals("--")) buf.append(token);
			} else {
				if (token.equals("\n")) {
					buf.append('\n');
				}
			}
			if (token.equals("\n") && current == PSTATE.comment_line) {
				String commnetLine = text.toLowerCase().trim();
				if (commnetLine.startsWith("block") || commnetLine.startsWith("statement")) {
					changeTo(PSTATE.sql_block);
				} else {
					changeTo(PSTATE.sql);
				}
			}
			if (token.equals("*/") && current == PSTATE.comment_block) changeTo(PSTATE.sql);
			if (token.equals("--") && current == PSTATE.sql) changeTo(PSTATE.comment_line);
			if (token.equals("--") && current == PSTATE.sql_block) changeTo(PSTATE.comment_line);
			if (token.equals("//") && current == PSTATE.sql) changeTo(PSTATE.comment_line);
			if (token.equals("/*") && current == PSTATE.sql) changeTo(PSTATE.comment_block);
			if (token.equals(delimiter) && current == PSTATE.sql) changeTo(PSTATE.sql);
			
			if (token.equals("\"") || token.equals("'")) {
				boolean bslash = ctx.getTextBefore().endsWith("\\");
				if (token.equals("\"")) {
					if (current == PSTATE.sql) current = PSTATE.inside_doublequot;
					else if (current == PSTATE.inside_doublequot && ! bslash) current = PSTATE.sql;
				}
				if (token.equals("'")) {
					if (current == PSTATE.sql) current = PSTATE.inside_singlequot;
					else if (current == PSTATE.inside_singlequot && ! bslash) current = PSTATE.sql;
				}
				if (current == PSTATE.sql) {
					buf.append(token);
				}
			}
			if (current == PSTATE.inside_singlequot || current == PSTATE.inside_doublequot) {
				buf.append(token);
			}
		}
		private void changeTo(PSTATE newState) {
			PatchStatement stm = null;
			if (current == PSTATE.sql || current == PSTATE.sql_block) {
				String sql = buf.toString().trim();
				if (sql.endsWith(delimiter) && current == PSTATE.sql) {
					sql = sql.substring(0, sql.length() - delimiter.length()).trim();
				}
				if (sql.startsWith(delimiter) && current == PSTATE.sql) {
					sql = sql.substring(delimiter.length()).trim();
				}
				if (sql.equalsIgnoreCase("commit") || sql.equalsIgnoreCase("rollback")) {
					throw new RuntimeException("Illegal sql statement - " + sql);
				}
				if (sql.length() > 2) {
					stm = isAllowedStatement(sql) ? new SqlPatchStatement(sql) : new DisallowedSqlPatchStatement(sql);
				} else if (sql.length() > 0) {
					LogFactory.getInstance().warn("Statement \"" + sql + "\" too short. Skiped.");
				}
			} else {
				stm = new CommentPatchStatement(buf.toString().trim());
			}
			if (stm != null) {
				statements.add(stm);
			}
			buf = new StringBuilder();
			current = newState;
		}
	
		public void documentEnds(SimpleParserCallbackContext ctx) {
			changeTo(null);
		}
	}
	
	private boolean isAllowedStatement(String sql) {
		sql = sql.trim().toLowerCase();
		if (sql.length() > 1) {
			return ! disallowed.contains(sql);
		}
		return true;
	}
}

