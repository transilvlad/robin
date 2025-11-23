package debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mimecast.robin.scanners.RspamdClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;

@Disabled
public class RspamdClientDebug {

    @Test
    public void scanFile() throws IOException {
        var path = Paths.get("example.eml");
        var client = new RspamdClient();
        var result = client.scanFile(path.toFile());
        System.out.println("Scan Result: " + prettyPrintJson(result));
    }

    /**
     * Pretty prints a JSON string or object.
     */
    private static String prettyPrintJson(Object json) {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonElement je;
            if (json instanceof String) {
                je = JsonParser.parseString((String) json);
            } else {
                String uglyJsonString = new Gson().toJson(json);
                je = JsonParser.parseString(uglyJsonString);
            }
            return gson.toJson(je);
        } catch (Exception e) {
            return json.toString();
        }
    }
}
