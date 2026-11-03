package dynamitedb.app.cli;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.error.DbmsException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class CliClient {
  public void run(String host, int port) throws IOException {
    run(
        host,
        port,
        new InputStreamReader(System.in, StandardCharsets.UTF_8),
        new PrintWriter(System.out, true),
        true);
  }

  public void run(String host, int port, Reader input, PrintWriter output, boolean prompts)
      throws IOException {
    try (RemoteQueryClient client = new RemoteQueryClient(host, port)) {
      if (prompts) output.println("Connected. End SQL with ';'; use exit or quit to leave.");
      SqlStatementReader statements = new SqlStatementReader(input);
      ResultRenderer renderer = new ResultRenderer();
      while (true) {
        if (prompts) {
          output.print("db> ");
          output.flush();
        }
        String sql = statements.next();
        if (sql == null) break;
        String command = sql.replaceFirst(";\\s*$", "").strip().toLowerCase(Locale.ROOT);
        if (command.equals("exit") || command.equals("quit") || command.equals(":quit")) break;
        try {
          renderer.print(client.execute(sql), output);
        } catch (DbmsException failure) {
          output.println("ERROR [" + failure.getCode() + "]: " + failure.getMessage());
          output.flush();
        }
      }
    }
  }
}
