package custom.ApiServer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.LoginServerThread;
import org.l2jmobius.gameserver.network.loginserverpackets.game.ServerStatus;
import org.l2jmobius.gameserver.Shutdown;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SpawnData;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.DBSpawnManager;
import org.l2jmobius.gameserver.managers.GrandBossManager;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.EtcItem;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.ExtractableProduct;
import org.l2jmobius.gameserver.model.ExtractableProductItem;
import org.l2jmobius.gameserver.model.item.holders.ItemSkillHolder;
import org.l2jmobius.gameserver.model.item.type.EtcItemType;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.clan.ClanMember;
import org.l2jmobius.gameserver.model.item.enums.ItemLocation;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.actor.enums.npc.RaidBossStatus;
import org.l2jmobius.gameserver.model.Spawn;
import org.l2jmobius.gameserver.model.spawns.SpawnTemplate;
import org.l2jmobius.gameserver.model.spawns.NpcSpawnTemplate;

import java.util.stream.Collectors;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;       
import java.io.BufferedReader;          
import java.io.InputStreamReader;        
import java.sql.Connection;              
import java.sql.PreparedStatement;       
import java.sql.ResultSet;               
import java.security.MessageDigest;      
import java.util.Base64;    
import java.sql.SQLException; 
import java.net.Socket;
import java.util.logging.Logger;
import java.util.Map;
import java.util.HashMap;
import java.time.Duration;

public class ApiServer {
    private static final String AUTH_TOKEN = "4d841c8f847abe141620434b949cc89a94a7ca2ecbad7c071e3ad1fb72092171a3000267ac10fd4cfc92531931fbf41e";
    private static HttpServer server;
    private static final Logger LOGGER = Logger.getLogger(ApiServer.class.getName());

    public ApiServer() {
        try {
            if (server == null) {
                server = HttpServer.create(new InetSocketAddress(8080), 0);
                server.createContext("/api/server/raidboss", new RaidBossListHandler());                
                server.createContext("/api/server/shutdown", new ShutdownGameHandler());
                server.createContext("/api/auth/register", new RegisterHandler());
                server.createContext("/api/login/status", new LoginServerStatusHandler());
                server.createContext("/api/auth/changepwd", new ChangePasswordHandler());
                server.createContext("/api/server/online", new StatusHandler());
                server.createContext("/api/server/players", new PlayersHandler());
                server.createContext("/api/server/items", new ItemListHandler());
                server.createContext("/api/server/items/extra", new ItemExtraHandler());
                server.createContext("/api/server/skills", new SkillListHandler());
                server.createContext("/api/player/giveitem", new GiveItemHandler());
                server.createContext("/api/player/info", new InventoryAndPaperdollHandler()); //inventory and Paperdoll info
                server.createContext("/api/player/warehouse", new WarehouseHandler());
                server.setExecutor(null);
                server.start();
                System.out.println("\u2714 API Server started on port 8080");

                Runtime.getRuntime().addShutdownHook(new Thread(ApiServer::stopHttpServer));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void stopHttpServer() {
        if (server != null) {
            System.out.println("Parando o servidor HTTP...");
            server.stop(0);
        } else {
            System.out.println("Servidor HTTP já está parado ou não foi iniciado.");
        }
    }

    private static boolean isAuthorized(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return authorization != null && authorization.startsWith("Bearer ") &&
               authorization.substring(7).equals(AUTH_TOKEN);
    }

    private static void sendResponse(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }

            long count = World.getInstance().getPlayers().stream()
                .filter(p -> p != null && p.getClient() != null && p.getClient().isConnected())
                .count();

            String response = "{ \"online\": " + count + " }";
            sendResponse(exchange, 200, response);
        }
    }
    
    public static Player getPlayerOnlineOrOffline(String playerName)
    {
        // Primeiro, verifica se o jogador está online
        Player player = World.getInstance().getPlayer(playerName);
        if (player != null)
        {
            return player;
        }

        // Se não estiver online, tenta carregar o jogador offline do banco de dados
        int objectId = CharInfoTable.getInstance().getIdByName(playerName);
        if (objectId > 0)
        {
            player = Player.load(objectId);
            return player;
        }

        return null;  // Retorna null se não encontrar o jogador nem online nem offline
    }

    private static class PlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }

            String playersJson = World.getInstance().getPlayers().stream()
                .filter(p -> p != null && p.getClient() != null && p.getClient().isConnected())
                .map(p -> "{\"name\":\"" + p.getName() + "\"}")
                .reduce((a, b) -> a + "," + b).orElse("");

            String response = "{ \"players\": [" + playersJson + "] }";
            sendResponse(exchange, 200, response);
        }
    }

private static class GiveItemHandler implements HttpHandler {
    
   @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json");

        try (InputStream is = exchange.getRequestBody();
             OutputStream os = exchange.getResponseBody();
             OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw)) {

            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            String[] parts = body.replace("\"", "").replace("{", "").replace("}", "").split(",");
            String target = null;
            int itemId = 0;
            long amount = 0;

            for (String part : parts) {
                String[] keyValue = part.split(":");
                if (keyValue.length != 2) continue;

                String key = keyValue[0].trim();
                String value = keyValue[1].trim();

                if ("target".equals(key)) {
                    target = value;
                } else if ("itemId".equals(key)) {
                    itemId = Integer.parseInt(value);
                } else if ("amount".equals(key)) {
                    amount = Long.parseLong(value);
                }
            }

            if (target == null || itemId == 0 || amount == 0) {
                exchange.sendResponseHeaders(400, 0);
                writer.write("{\"error\":\"Invalid parameters\"}");
                return;
            }

            Player player = World.getInstance().getPlayer(target);
            if (player == null) {
                exchange.sendResponseHeaders(404, 0);
                writer.write("{\"error\":\"Player not found\"}");
                return;
            }

            player.addItem(ItemProcessType.REWARD, itemId, amount, player, true);

            ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
            String itemName = (item != null && item.getName() != null) ? item.getName().replace("\"", "\\\"") : "Unknown";

            exchange.sendResponseHeaders(200, 0);
            writer.write("{");
            writer.write("\"status\":\"OK\",");
            writer.write("\"target\":\"" + target + "\",");
            writer.write("\"itemId\":" + itemId + ",");
            writer.write("\"itemName\":\"" + itemName + "\",");
            writer.write("\"amount\":" + amount);
            writer.write("}");

        } catch (Exception e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(400, 0);
            try (OutputStream os = exchange.getResponseBody();
                 OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw)) {
                writer.write("{\"error\":\"Invalid JSON format\"}");
            }
        }
    }
}

private static class ItemListHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json");

        try {
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody();
                 OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw)) {

                writer.write("[");
                // Paralelizando o processamento de itens
                boolean first = true;
                String itemListJson = Arrays.stream(ItemData.getInstance().getAllItems())
                        .parallel()
                        .filter(item -> item != null && item.getName() != null)
                        .map(item -> buildItemJson(item, first))
                        .collect(Collectors.joining(","));
                writer.write(itemListJson);
                writer.write("]");
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{ \"error\": \"Server error\" }");
        }
    }

    // Método auxiliar para gerar o JSON de cada item
    private String buildItemJson(ItemTemplate item, boolean first) {
        StringBuilder json = new StringBuilder();

        if (!first) {
            json.append(",");
        }

        String itemName = item.getName().replace("\"", "\\\"");
        String icon = (item.getIcon() != null) ? item.getIcon() : "default.png";
        int weight = item.getWeight();
        String itemCategory = getItemCategory(item);

        json.append("{")
            .append("\"id\":").append(item.getId()).append(",")
            .append("\"weight\":").append(weight).append(",")
            .append("\"name\":\"").append(itemName).append("\",")
            .append("\"type\":\"").append(itemCategory).append("\",")
            .append("\"icon\":\"").append(icon).append("\"")
            .append("}");

        return json.toString();
    }

    // Método auxiliar para determinar a categoria do item
    private String getItemCategory(ItemTemplate item) {
        if (item instanceof Weapon) return "Weapon";
        if (item instanceof Armor) return "Armor";
        if (item instanceof EtcItem) return "EtcItem";
        return "Unknown";
    }
}

private static class ItemExtraHandler implements HttpHandler {

   @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json");

        try {
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody();
                 OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw)) {

                writer.write("{");
                // Paralelizando o processamento dos itens
                boolean firstItem = true;
                String itemDetailsJson = Arrays.stream(ItemData.getInstance().getAllItems())
                        .parallel()
                        .filter(Objects::nonNull)
                        .map(item -> buildItemExtraJson(item, firstItem))
                        .collect(Collectors.joining(","));
                writer.write(itemDetailsJson);
                writer.write("}");
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{ \"error\": \"Server error\" }");
        }
    }

    // Método auxiliar para gerar o JSON extra de cada item
    private String buildItemExtraJson(ItemTemplate item, boolean firstItem) {
        StringBuilder json = new StringBuilder();

        if (!firstItem) {
            json.append(",");
        }

        json.append("\"").append(item.getId()).append("\":{")
            .append("\"weight\":").append(item.getWeight()).append(",")
            .append("\"price\":").append(item.getReferencePrice()).append(",")
            .append("\"crystal_type\":\"").append(item.getCrystalType() != null ? item.getCrystalType().toString() : "none").append("\",")
            .append("\"crystal_count\":").append(item.getCrystalCount()).append(",")
            .append("\"is_tradable\":").append(item.isTradeable()).append(",")
            .append("\"is_dropable\":").append(item.isDropable()).append(",")
            .append("\"is_destroyable\":").append(item.isDestroyable()).append(",")
            .append("\"is_sellable\":").append(item.isSellable()).append(",")
            .append("\"default_action\":\"").append(item.getDefaultAction() != null ? item.getDefaultAction().toString() : "").append("\"");

        // Adiciona skills
        if (item.getAllSkills() != null && !item.getAllSkills().isEmpty()) {
            json.append(",\"item_skills\":[").append(buildSkillsJson(item)).append("]");
        }

        // Adiciona etc extras
        if (item instanceof EtcItem) {
            EtcItem etc = (EtcItem) item;
            json.append(",\"etc_type\":\"").append(etc.getItemType() != null ? etc.getItemType().name() : "Unknown").append("\"");
            json.append(",\"handler\":\"").append(etc.getHandlerName() != null ? etc.getHandlerName() : "").append("\"");
            json.append(",\"infinite\":").append(etc.isInfinite());
            json.append(",\"extractableMin\":").append(etc.getExtractableCountMin());
            json.append(",\"extractableMax\":").append(etc.getExtractableCountMax());
            json.append(",\"capsuled_items\":[").append(buildCapsuledItemsJson(etc)).append("]");
        }

        json.append("}");

        return json.toString();
    }

    // Método auxiliar para gerar as skills em JSON
    private String buildSkillsJson(ItemTemplate item) {
        return item.getAllSkills().stream()
            .map(skillHolder -> {
                Skill skill = SkillData.getInstance().getSkill(skillHolder.getSkillId(), skillHolder.getSkillLevel());
                return "{" +
                    "\"id\":" + skillHolder.getSkillId() + "," +
                    "\"level\":" + skillHolder.getSkillLevel() + "," +
                    (skill != null ? "\"name\":\"" + skill.getName().replace("\"", "\\\"") + "\"" : "") +
                    "}";
            })
            .collect(Collectors.joining(","));
    }

    // Método auxiliar para gerar os capsuled items
    private String buildCapsuledItemsJson(EtcItem etc) {
        List<ExtractableProduct> products = etc.getExtractableItems();
        return products.stream()
            .map(prod -> {
                ItemTemplate capsuledItem = ItemData.getInstance().getTemplate(prod.getId());
                String capsuledName = capsuledItem != null && capsuledItem.getName() != null
                    ? capsuledItem.getName().replace("\"", "\\\"")
                    : "Unknown";
                return "{" +
                    "\"id\":" + prod.getId() + "," +
                    "\"name\":\"" + capsuledName + "\"," +
                    "\"min\":" + prod.getMin() + "," +
                    "\"max\":" + prod.getMax() + "," +
                    "\"chance\":" + prod.getChance() +
                    "}";
            })
            .collect(Collectors.joining(","));
    }
}

private static class InventoryAndPaperdollHandler implements HttpHandler {
 
   @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        
        String[] parts = exchange.getRequestURI().getPath().split("/");
        if (parts.length < 5) {
            sendResponse(exchange, 400, "{ \"error\": \"Player name missing in path.\" }");
            return;
        }

        String playerName = parts[4];
        Player player = World.getInstance().getPlayer(playerName);
        boolean offlineLoaded = false;

        if (player == null) {
            int objectId = CharInfoTable.getInstance().getIdByName(playerName);
            if (objectId > 0) {
                player = Player.load(objectId);
                offlineLoaded = true;
            } else {
                sendResponse(exchange, 404, "{ \"error\": \"Player not found.\" }");
                return;
            }
        }

        // Obter informações básicas do jogador (HP, MP, etc.)
        double currentHp = player.getCurrentHp();
        double currentMp = player.getCurrentMp();
        double currentCp = player.getCurrentCp();
        long maxHp = player.getMaxHp();
        long maxMp = player.getMaxMp();
        long maxCp = player.getMaxCp();
        int level = player.getLevel();
        long experience = player.getExp();
        long specialpoints = player.getSp();
        String playerTitle = player.getTitle();
        boolean online = player.isOnline();
        boolean noble = player.isNoble();
        // Obter a classe do jogador corretamente através de getPlayerClass
        PlayerClass playerClass = player.getPlayerClass();
        int classId = playerClass.getId();

        // Obter a informação de classe do ClassListData
        ClassInfoHolder classInfo = ClassListData.getInstance().getClassList().get(playerClass);

        // Verifica se a classe foi encontrada
        String className = (classInfo != null) ? classInfo.getClassName() : "Unknown";
        String race = player.getRace().toString(); // Pode ser modificado para outro formato, se necessário

        // Monta a resposta do inventário com as informações básicas do jogador
        Collection<Item> items = player.getInventory().getItems();
        StringBuilder response = new StringBuilder("{\"playerdata\":{");
        response.append("\"playerName\":\"").append(escapeJson(playerName)).append("\",")
                .append("\"playerTitle\":\"").append(escapeJson(playerTitle)).append("\",")
                .append("\"level\":").append(level).append(",")
                .append("\"exp\":").append(experience).append(",")
                .append("\"sp\":").append(specialpoints).append(",")
                .append("\"hp\":").append(currentHp).append(",")
                .append("\"maxhp\":").append(maxHp).append(",")
                .append("\"mp\":").append(currentMp).append(",")
                .append("\"maxmp\":").append(maxMp).append(",")
                .append("\"cp\":").append(currentCp).append(",")
                .append("\"maxcp\":").append(maxCp).append(",")
                .append("\"race\":\"").append(escapeJson(race)).append("\",")
                .append("\"class\":\"").append(escapeJson(className)).append("\",")
                .append("\"online\":").append(online).append(",")
                .append("\"noble\":").append(noble)
                .append("},")
                .append("\"inventory\":[");

        boolean first = true;
        for (Item item : items) {
            if (item.isEquipped()) continue;

            if (!first) {
                response.append(",");
            }
            first = false;

            response.append("{")
                    .append("\"itemId\":").append(item.getId()).append(",")
                    .append("\"itemName\":\"").append(escapeJson(item.getItemName())).append("\",")
                    .append("\"itemCount\":").append(item.getCount()).append(",")
                    .append("\"itemIcon\":\"").append(escapeJson(item.getTemplate().getIcon())).append("\"")
                    .append("}");
        }

        response.append("],");

        // Adiciona o paperdoll
        Collection<Item> paperdollItems = new ArrayList<>();
        for (Item item : items) {
            if (item != null && item.getItemLocation() == ItemLocation.PAPERDOLL) {
                paperdollItems.add(item);
            }
        }

        response.append("\"paperdoll\":[");
        first = true;

        for (Item item : paperdollItems) {
            if (!first) {
                response.append(",");
            }
            first = false;

            String itemIcon = item.getTemplate().getIcon();

            response.append("{")
                    .append("\"itemId\":").append(item.getId()).append(",")
                    .append("\"itemName\":\"").append(escapeJson(item.getItemName())).append("\",")
                    .append("\"itemCount\":").append(item.getCount()).append(",")
                    .append("\"itemIcon\":\"").append(escapeJson(itemIcon)).append("\"")
                    .append("}");
        }

        response.append("]}");

        sendResponse(exchange, 200, response.toString());

        // Cleanup se o player foi carregado offline
        if (offlineLoaded) {
            player.deleteMe(); // Libera recursos
        }
    }

    private static String escapeJson(String str) {
        return str == null ? "" : str.replace("\\", "\\\\")
                                     .replace("\"", "\\\"")
                                     .replace("/", "\\/")
                                     .replace("\b", "\\b")
                                     .replace("\f", "\\f")
                                     .replace("\n", "\\n")
                                     .replace("\r", "\\r")
                                     .replace("\t", "\\t");
    }
}

private static class WarehouseHandler implements HttpHandler {

   @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        String[] parts = exchange.getRequestURI().getPath().split("/");
        if (parts.length < 5) {
            sendResponse(exchange, 400, "{ \"error\": \"Player name missing in path.\" }");
            return;
        }

        String playerName = parts[4];
        Player player = World.getInstance().getPlayer(playerName);
        if (player == null) {
            sendResponse(exchange, 404, "{ \"error\": \"Player not found.\" }");
            return;
        }

        // Acessa os itens no armazém do jogador
        Collection<Item> items = player.getWarehouse().getItems();
        StringBuilder response = new StringBuilder("[");
        boolean first = true;
        for (Item item : items) {
            if (!first) response.append(",");
            first = false;
            String itemIcon = item.getTemplate().getIcon();  // Aqui está o ajuste
            response.append("{\"itemId\":").append(item.getId())
                    .append(",\"itemName\":").append(item.getItemName())
                    .append(",\"itemCount\":").append(item.getCount())
                    .append(",\"itemIcon\":").append(itemIcon)
                    .append("}");
        }
        response.append("]");
        sendResponse(exchange, 200, response.toString());
    }
}

private static class SkillListHandler implements HttpHandler {
    private static final int PAGE_SIZE = 9000099; // Número de skills por página

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        // Pegando o número da página da requisição (caso não venha, assumimos página 1)
        String query = exchange.getRequestURI().getQuery();
        int pageNumber = 1;
        if (query != null && query.contains("page=")) {
            pageNumber = Integer.parseInt(query.split("=")[1]);
        }

        int startSkillId = (pageNumber - 1) * PAGE_SIZE + 1; // Calcula o skillId de início
        int endSkillId = startSkillId + PAGE_SIZE - 1; // Calcula o skillId de término

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream os = exchange.getResponseBody();
             OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw)) {

            writer.write("[");
            boolean first = true;
            boolean skillsAvailable = false;

            // Itera sobre as skills dentro do intervalo da página
            for (int skillId = startSkillId; skillId <= endSkillId; skillId++) {
                int maxLevel = SkillData.getInstance().getMaxLevel(skillId);

                if (maxLevel == 0) {
                    continue; // Se não há skill para esse skillId, pula para o próximo
                }

                // Iteração para cada nível de skill
                for (int level = 1; level <= maxLevel; level++) {
                    Skill skill = SkillData.getInstance().getSkill(skillId, level);

                    if (skill == null) {
                        break;  // Nenhuma skill encontrada para este level, já podemos sair do loop.
                    }

                    if (!first) {
                        writer.write(",");
                    }
                    first = false;

                    // Escreve a skill no formato JSON
                    writer.write("{");
                    writer.write("\"skillId\":" + skillId + ",");
                    writer.write("\"level\":" + level + ",");
                    writer.write("\"type\":\"" + skill.getTargetType() + "\",");
                    writer.write("\"toLevel\":" + maxLevel + ",");
                    writer.write("\"name\":\"" + skill.getName().replace("\"", "\\\"") + "\",");
                    writer.write("\"icon\":\"" + (skill.getIcon() != null ? skill.getIcon() : "default_icon.png") + "\"");
                    writer.write("}");

                    skillsAvailable = true;
                }
            }

            writer.write("]");

            // Se não houver skills na página, podemos informar o cliente
            if (!skillsAvailable) {
                sendResponse(exchange, 404, "{\"error\":\"No more skills available.\"}");
            }

        } catch (Exception e) {
            e.printStackTrace();  // Imprime qualquer exceção no console
            sendResponse(exchange, 500, "{\"error\":\"Internal server error.\"}");
        }
    }
}

public class RaidBossListHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json");

        try {
            List<RaidBoss> raidBosses = new ArrayList<>();
            List<RaidBoss> grandBosses = new ArrayList<>();
            List<RaidBoss> liveBosses = new ArrayList<>();

            // Obter os templates
            Collection<NpcTemplate> templates = NpcData.getInstance().getTemplates(
                npc -> npc != null && (
                    "RaidBoss".equalsIgnoreCase(npc.getType()) ||
                    "GrandBoss".equalsIgnoreCase(npc.getType()) ||
                    "Monster".equalsIgnoreCase(npc.getType())
                )
            );

            for (NpcTemplate npcTemplate : templates) {
                final int npcId = npcTemplate.getId();
                final String type = npcTemplate.getType();
                final int level = npcTemplate.getLevel();
                final String name = npcTemplate.getName();

                // Verificar se o NPC tem DBSave
                final List<NpcSpawnTemplate> spawns = SpawnData.getInstance().getNpcSpawns(
                npc -> npc.getId() == npcId && npc.hasDBSave()
                );
                boolean hasDBSave = spawns.stream().anyMatch(NpcSpawnTemplate::hasDBSave);

                // Status do NPC no DBSpawnManager (não será usado diretamente para verificar DBSave)
                RaidBossStatus status = DBSpawnManager.getInstance().getStatus(npcId);
                boolean alive = (status == RaidBossStatus.ALIVE || status == RaidBossStatus.COMBAT);

                // --- GRAND BOSSES ---
                if ("GrandBoss".equalsIgnoreCase(type)) {
                    StatSet grandBossStats = GrandBossManager.getInstance().getStatSet(npcId);
                    boolean isDummy = (grandBossStats == null); // Dummy = não controlado

                    RaidBoss boss = new RaidBoss(npcId, name, alive, status, level, "GrandBoss");
                    boss.setDummy(isDummy);

                    if (isDummy) {
                        // NPC é um dummy: vai para liveBosses (lista de NPCs mortos/não spawnados)
                        liveBosses.add(boss);
                    } else {
                        // GrandBoss real: vai sempre para grandBosses, esteja vivo ou não
                        if (!alive) {
                            long respawnTime = grandBossStats.getLong("respawn_time", 0);
                            if (respawnTime > System.currentTimeMillis()) {
                                boss.setRespawnTime(respawnTime);
                            }
                        }

                        grandBosses.add(boss);
                    }

                    continue;
                }

                // Para RaidBosses
                if ("RaidBoss".equalsIgnoreCase(type)) {
                    // RaidBoss com DBSave vai para raidBosses, se não, vai para liveBosses
                    RaidBoss boss = new RaidBoss(npcId, name, (status == RaidBossStatus.ALIVE || status == RaidBossStatus.COMBAT), status, level, "RaidBoss");
                    
                if (hasDBSave) {
                    // RaidBoss com DBSave: vai sempre para raidBosses (vivo ou morto)
                    raidBosses.add(boss);
                } else {
                    if (alive) {
                        // RaidBoss sem DBSave e vivo: sobe para raidBosses
                        raidBosses.add(boss);
                    } else {
                        // RaidBoss sem DBSave e morto: desce para liveBosses
                        liveBosses.add(boss);
                    }
                  }
                }

                // Para Monsters, somente se tiver DBSave
                if ("Monster".equalsIgnoreCase(type) && hasDBSave) {
                    // Monsters com DBSave: vai para liveBosses
                    RaidBoss boss = new RaidBoss(npcId, name, (status == RaidBossStatus.ALIVE || status == RaidBossStatus.COMBAT), status, level, "Monster");
                    raidBosses.add(boss);
                }
            }

            // Construir a resposta
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody();
                 OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw)) {

                writer.write("{");
                writer.write("\"raidBosses\": [");
                writer.write(raidBosses.stream().map(this::buildJson).collect(Collectors.joining(",")));
                writer.write("],");
                writer.write("\"grandBosses\": [");
                writer.write(grandBosses.stream().map(this::buildJson).collect(Collectors.joining(",")));
                writer.write("],");
                writer.write("\"bosses\": [");
                writer.write(liveBosses.stream().map(this::buildJson).collect(Collectors.joining(",")));
                writer.write("]");
                writer.write("}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"error\":\"Server error\"}");
        }
    }

    private String buildJson(RaidBoss boss) {
        String statusText = boss.isAlive() ? "Vivo" : "Morto";
        if (boss.isAlive() && boss.getStatus() == RaidBossStatus.COMBAT) {
            statusText = "Vivo (Em combate)";
        }

        StringBuilder json = new StringBuilder();
        json.append("{")
            .append("\"npcId\":").append(boss.getNpcId()).append(",")
            .append("\"name\":\"").append(boss.getName()).append("\",")
            .append("\"status\":\"").append(statusText).append("\",")
            .append("\"level\":").append(boss.getLevel()).append(",")
            .append("\"type\":\"").append(boss.getType()).append("\"");

        if (boss.isDummy()) {
            json.append(",\"dummy\":true");
        }

        if (!boss.isAlive() && boss.getRespawnTime() > 0) {
            json.append(",\"respawnAt\":").append(boss.getRespawnTime());
        }

        json.append("}");
        return json.toString();
    }
}

public class RaidBoss
{
    private final int npcId;
    private final String name;
    private final boolean alive;
    private final RaidBossStatus status;
    private final int level;
    private String type;
    private long respawnTime = 0;
    private boolean dummy = false;

    public RaidBoss(int npcId, String name, boolean alive, RaidBossStatus status, int level, String type)
    {
        this.npcId = npcId;
        this.name = name;
        this.alive = alive;
        this.status = status;
        this.level = level;
        this.type = type;
    }

    public int getNpcId() { return npcId; }
    public String getName() { return name; }
    public boolean isAlive() { return alive; }
    public RaidBossStatus getStatus() { return status; }
    public int getLevel() { return level; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public long getRespawnTime() { return respawnTime; }
    public void setRespawnTime(long respawnTime) { this.respawnTime = respawnTime; }
    public boolean isDummy() { return dummy; }
    public void setDummy(boolean dummy) { this.dummy = dummy; }
}

private static class RegisterHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }


        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            // Usando JSON Parsing direto
            String login = null;
            String password = null;

            try {
                String[] parts = body.replace("\"", "").replace("{", "").replace("}", "").split(",");
                for (String part : parts) {
                    String[] keyValue = part.split(":");
                    if (keyValue.length == 2) {
                        if (keyValue[0].trim().equals("login")) login = keyValue[1].trim().toLowerCase();
                        if (keyValue[0].trim().equals("password")) password = keyValue[1].trim();
                    }
                }
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid JSON format\"}");
                return;
            }

            if (login == null || password == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing login or password\"}");
                return;
            }

            String encryptedPassword = encryptPasswordSHA(password);

            try (Connection con = DatabaseFactory.getConnection();
                 PreparedStatement checkStmt = con.prepareStatement("SELECT COUNT(*) FROM accounts WHERE login = ?")) {
                checkStmt.setString(1, login);
                ResultSet rs = checkStmt.executeQuery();

                if (rs.next() && rs.getInt(1) > 0) {
                    sendResponse(exchange, 409, "{\"error\":\"User already exists\"}");  // 409 Conflict
                    return;
                }

                try (PreparedStatement stmt = con.prepareStatement(
                        "INSERT INTO accounts (login, password, accessLevel) VALUES (?, ?, 0)")) {
                    stmt.setString(1, login);
                    stmt.setString(2, encryptedPassword);
                    stmt.executeUpdate();
                }

                sendResponse(exchange, 201, "{\"status\":\"Account created\"}");

            } catch (SQLException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"Database error\"}");
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"error\":\"Server error\"}");
        }
    }

    private String encryptPasswordSHA(String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA");
        byte[] hashedBytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hashedBytes);
    }
}

private static class LoginHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String[] parts = body.replace("\"", "").replace("{", "").replace("}", "").split(",");

            String login = null;
            String password = null;

            for (String part : parts) {
                String[] keyValue = part.split(":");
                if (keyValue.length != 2) continue;
                if (keyValue[0].trim().equals("login")) login = keyValue[1].trim().toLowerCase();
                if (keyValue[0].trim().equals("password")) password = keyValue[1].trim();
            }

            if (login == null || password == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing login or password\"}");
                return;
            }

            // Verifica se o login existe no banco
            try (Connection con = DatabaseFactory.getConnection();
                 PreparedStatement stmt = con.prepareStatement("SELECT password FROM accounts WHERE login = ?")) {
                stmt.setString(1, login);
                ResultSet rs = stmt.executeQuery();

                if (!rs.next()) {
                    sendResponse(exchange, 401, "{\"error\":\"Invalid login or password\"}");
                    return;
                }

                // Recupera a senha armazenada e compara com a fornecida
                String storedPassword = rs.getString("password");
                if (!storedPassword.equals(encryptPasswordSHA(password))) {
                    sendResponse(exchange, 401, "{\"error\":\"Invalid login or password\"}");
                    return;
                }
            }

            sendResponse(exchange, 200, "{\"status\":\"Login successful\"}");
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"error\":\"Server error\"}");
        }
    }

    private String encryptPasswordSHA(String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA");
        byte[] hashedBytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hashedBytes);
    }
}

private static class ChangePasswordHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String[] parts = body.replace("\"", "").replace("{", "").replace("}", "").split(",");

            String login = null;
            String oldPassword = null;
            String newPassword = null;

            for (String part : parts) {
                String[] keyValue = part.split(":");
                if (keyValue.length != 2) continue;
                if (keyValue[0].trim().equals("login")) login = keyValue[1].trim().toLowerCase();
                if (keyValue[0].trim().equals("oldPassword")) oldPassword = keyValue[1].trim();
                if (keyValue[0].trim().equals("newPassword")) newPassword = keyValue[1].trim();
            }

            if (login == null || oldPassword == null || newPassword == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing fields\"}");
                return;
            }

            // Verifica se o login existe no banco
            try (Connection con = DatabaseFactory.getConnection();
                 PreparedStatement stmt = con.prepareStatement("SELECT password FROM accounts WHERE login = ?")) {
                stmt.setString(1, login);
                ResultSet rs = stmt.executeQuery();

                if (!rs.next()) {
                    sendResponse(exchange, 401, "{\"error\":\"User not found\"}");
                    return;
                }

                // Recupera a senha armazenada e compara com a fornecida
                String storedPassword = rs.getString("password");
                if (!storedPassword.equals(encryptPasswordSHA(oldPassword))) {
                    sendResponse(exchange, 401, "{\"error\":\"Invalid old password\"}");
                    return;
                }

                // Atualiza a senha no banco
                String encryptedNewPassword = encryptPasswordSHA(newPassword);
                try (PreparedStatement updateStmt = con.prepareStatement("UPDATE accounts SET password = ? WHERE login = ?")) {
                    updateStmt.setString(1, encryptedNewPassword);
                    updateStmt.setString(2, login);
                    updateStmt.executeUpdate();
                }
            }

            sendResponse(exchange, 200, "{\"status\":\"Password changed successfully\"}");
        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "{\"error\":\"Server error\"}");
        }
    }

    private String encryptPasswordSHA(String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA");
        byte[] hashedBytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hashedBytes);
    }
}

private static class ShutdownGameHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!ApiServer.isAuthorized(exchange)) {
            sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        int delay = 10;
        boolean restart = false;
        boolean abort = false;

        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            body = body.trim().replaceAll("[{}\"\\s]", ""); // remove { } " e espaços

            if (!body.isEmpty()) {
                String[] entries = body.split(",");
                for (String entry : entries) {
                    String[] kv = entry.split(":");
                    if (kv.length != 2) continue;
                    String key = kv[0].toLowerCase();
                    String value = kv[1].toLowerCase();

                    if (key.equals("delay")) {
                        try {
                            delay = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {}
                    } else if (key.equals("restart")) {
                        restart = value.equals("true");
                    } else if (key.equals("abort")) {
                        abort = value.equals("true");
                    }
                }
            }
        } catch (Exception e) {
            sendResponse(exchange, 400, "{\"error\":\"Invalid request body\"}");
            return;
        }

        if (abort) {
            Shutdown.getInstance().abort(null);
            sendResponse(exchange, 200, "{\"status\":\"Shutdown aborted\"}");
            return;
        }

        Shutdown.getInstance().startShutdown(null, delay, restart);
        String msg = restart ? "Restarting" : "Shutting down";
        sendResponse(exchange, 200, "{\"status\":\"" + msg + " in " + delay + " seconds\"}");
    }
}

public class LoginServerStatusHandler implements HttpHandler
{
	private static final String LOGIN_SERVER_HOST = "127.0.0.1";
	private static final int LOGIN_SERVER_PORT = 9014;

	@Override
	public void handle(HttpExchange exchange) throws IOException
	{
		if (!ApiServer.isAuthorized(exchange))
		{
			ApiServer.sendResponse(exchange, 403, "{\"error\":\"Unauthorized\"}");
			return;
		}

		if (!exchange.getRequestMethod().equalsIgnoreCase("GET"))
		{
			ApiServer.sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
			return;
		}

		final boolean online = isLoginServerOnline();
		final String status = online ? "online" : "offline";
		ApiServer.sendResponse(exchange, 200, "{\"status\":\"" + status + "\"}");
	}

	private boolean isLoginServerOnline()
	{
		try (Socket socket = new Socket())
		{
			socket.connect(new InetSocketAddress(LOGIN_SERVER_HOST, LOGIN_SERVER_PORT), 1000);
			return true;
		}
		catch (IOException e)
		{
			return false;
		}
	}
}

    public static void main(String[] args) {
        new ApiServer();
    }
}

