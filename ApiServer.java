package custom.apiserver;

import custom.apiserver.util.JsonBuilder;

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
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.DBSpawnManager;
import org.l2jmobius.gameserver.managers.GrandBossManager;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.EtcItem;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.enums.player.SocialClass;
import org.l2jmobius.gameserver.model.actor.enums.player.SubclassType;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.SkillLearn;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.SkillOperateType;
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
import org.l2jmobius.commons.util.StringUtil;
import org.l2jmobius.gameserver.model.quest.Quest;
import org.l2jmobius.gameserver.handler.AdminCommandHandler;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;

import java.util.stream.Collectors;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Optional;
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

public class ApiServer extends Quest implements IAdminCommandHandler
{
	private static final String AUTH_TOKEN = "4d841c8f847abe141620434b949cc89a94a7ca2ecbad7c071e3ad1fb72092171a3000267ac10fd4cfc92531931fbf41e";
	private static HttpServer server;
	private static final Logger LOGGER = Logger.getLogger(ApiServer.class.getName());
	private static boolean isRunning = false;
	private static final int PORT = 8080;
	private static final String STOP_HTTP = "admin_currentBoss"; // KKKKK

	public ApiServer()
	{
		super(-1);
		AdminCommandHandler.getInstance().registerHandler(this);

		if (isRunning)
		{
			stopHttpServer();
			try
			{
				Thread.sleep(200); // tempo pra liberar a porta
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}

		try
		{
			server = HttpServer.create(new InetSocketAddress(PORT), 0);
			registerEndpoints(server);
			server.setExecutor(null); // default executor
			server.start();
			isRunning = true;

			LOGGER.info("✔ API Server started on port " + PORT);

			Runtime.getRuntime().addShutdownHook(new Thread(() -> stopHttpServer()));
		}
		catch (IOException e)
		{
			LOGGER.severe("❌ Erro ao iniciar o API Server: " + e.getMessage());
		}
	}

	public static void stopHttpServer()
	{
		if (server != null)
		{
			LOGGER.warning("Parando o servidor HTTP...");
			server.stop(0);
			isRunning = false;
		}
		else
		{
			LOGGER.warning("Servidor HTTP já está parado ou não foi iniciado.");
		}
	}

	public static boolean isRunning()
	{
		return isRunning;
	}

	@Override
	public boolean useAdminCommand(String command, Player player)
	{
		if (command.equalsIgnoreCase(STOP_HTTP))
		{
			if (ApiServer.isRunning())
			{
				ApiServer.stopHttpServer();
				player.sendMessage("Parando o servidor HTTP do ApiServer.");
				LOGGER.warning("✔ httpServer encerrado via admin_currentBoss.");
			}
			else
			{
				player.sendMessage("O servidor HTTP já está parado.");
				LOGGER.info("✔ httpServer já estava parado.");
			}
			return true;
		}
		return false;
	}

	@Override
	public String[] getAdminCommandList()
	{
		return new String[]
		{
			STOP_HTTP
		};
	}

	private void registerEndpoints(HttpServer server)
	{
		Map<String,HttpHandler> endpoints = new HashMap<>();
		endpoints.put("/api/server/raidboss", new RaidBossListHandler());
		endpoints.put("/api/server/skilltree", new SkillTreeHandler());
		endpoints.put("/api/server/shutdown", new ShutdownGameHandler());
		endpoints.put("/api/auth/register", new RegisterHandler());
		endpoints.put("/api/login/status", new LoginServerStatusHandler());
		endpoints.put("/api/auth/changepwd", new ChangePasswordHandler());
		endpoints.put("/api/server/online", new StatusHandler());
		endpoints.put("/api/server/players", new PlayersHandler());
		endpoints.put("/api/server/items", new ItemListHandler());
		endpoints.put("/api/server/items/extra", new ItemExtraHandler());
		endpoints.put("/api/server/skills", new SkillListHandler());
		endpoints.put("/api/player/giveitem", new GiveItemHandler());
		endpoints.put("/api/player/info", new InventoryAndPaperdollHandler());
		endpoints.put("/api/player/warehouse", new WarehouseHandler());

		for (Map.Entry<String,HttpHandler> entry : endpoints.entrySet())
		{
			server.createContext(entry.getKey(), entry.getValue());
		}
	}

	public static boolean isAuthorized(HttpExchange exchange)
	{
		String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
		if (authHeader == null || !authHeader.startsWith("Bearer "))
			return false;
		return AUTH_TOKEN.equals(authHeader.substring(7));
	}

	public static void sendResponse(HttpExchange exchange, int code, String json) throws IOException
	{
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(code, bytes.length);
		try (OutputStream os = exchange.getResponseBody())
		{
			os.write(bytes);
		}
	}

	/**
	 * Retorna o Player online pelo nome, ou carrega o Player offline do banco se
	 * não estiver online.
	 * 
	 * @param playerName Nome do jogador
	 * @return Instância do Player online ou offline, ou null se não encontrado
	 */
	public static Player getPlayerOnlineOrOffline(String playerName)
	{
		// Tenta pegar o player online
		Player player = World.getInstance().getPlayer(playerName);
		if (player != null)
		{
			return player;
		}

		// Se não estiver online, tenta carregar offline pelo objectId do banco
		int objectId = CharInfoTable.getInstance().getIdByName(playerName);
		if (objectId > 0)
		{
			return Player.load(objectId);
		}

		// Player não encontrado
		return null;
	}

	private static class StatusHandler implements HttpHandler
	{
		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				String error = JsonBuilder.object().appendField("error", "Unauthorized").build();
				sendResponse(exchange, 403, error);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				String error = JsonBuilder.object().appendField("error", "Method Not Allowed").build();
				sendResponse(exchange, 405, error);
				return;
			}

			JsonBuilder json = JsonBuilder.object().appendField("status", "online").appendField("playersOnline", World.getInstance().getPlayers().size()).appendField("serverTime", System.currentTimeMillis());

			sendResponse(exchange, 200, json.build());
		}
	}

	private static class PlayersHandler implements HttpHandler
	{
		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				String error = JsonBuilder.object().appendField("error", "Unauthorized").build();
				sendResponse(exchange, 403, error);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				String error = JsonBuilder.object().appendField("error", "Method Not Allowed").build();
				sendResponse(exchange, 405, error);
				return;
			}

			JsonBuilder json = JsonBuilder.object();

			// Cria um array de jogadores
			JsonBuilder.JsonArrayBuilder playersArray = new JsonBuilder.JsonArrayBuilder();
			World.getInstance().getPlayers().stream().filter(p -> p != null && p.getClient() != null && p.getClient().isConnected()).forEach(p ->
			{
				JsonBuilder playerJson = JsonBuilder.object().appendField("name", p.getName());
				playersArray.addObject(playerJson);
			});

			json.appendArrayField("players", playersArray);

			sendResponse(exchange, 200, json.build());
		}
	}

	private static class GiveItemHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				String error = JsonBuilder.object().appendField("error", "Unauthorized").build();
				sendResponse(exchange, 403, error);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				String error = JsonBuilder.object().appendField("error", "Method Not Allowed").build();
				sendResponse(exchange, 405, error);
				return;
			}

			exchange.getResponseHeaders().set("Content-Type", "application/json");

			try (InputStream is = exchange.getRequestBody(); OutputStream os = exchange.getResponseBody(); OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8); BufferedWriter writer = new BufferedWriter(osw))
			{

				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

				// parsing simples - manter igual pra não quebrar
				String[] parts = body.replace("\"", "").replace("{", "").replace("}", "").split(",");
				String target = null;
				int itemId = 0;
				long amount = 0;

				for (String part : parts)
				{
					String[] keyValue = part.split(":");
					if (keyValue.length != 2)
						continue;

					String key = keyValue[0].trim();
					String value = keyValue[1].trim();

					if ("target".equals(key))
					{
						target = value;
					}
					else
						if ("itemId".equals(key))
						{
							itemId = Integer.parseInt(value);
						}
						else
							if ("amount".equals(key))
							{
								amount = Long.parseLong(value);
							}
				}

				if (target == null || itemId == 0 || amount == 0)
				{
					String error = JsonBuilder.object().appendField("error", "Invalid parameters").build();
					sendResponse(exchange, 400, error);
					return;
				}

				Player player = World.getInstance().getPlayer(target);
				if (player == null)
				{
					String error = JsonBuilder.object().appendField("error", "Player not found").build();
					sendResponse(exchange, 404, error);
					return;
				}

				player.addItem(ItemProcessType.REWARD, itemId, amount, player, true);

				ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
				String itemName = (item != null && item.getName() != null) ? item.getName() : "Unknown";

				String response = JsonBuilder.object().appendField("status", "OK").appendField("target", target).appendField("itemId", itemId).appendField("itemName", itemName).appendField("amount", amount).build();

				sendResponse(exchange, 200, response);

			}
			catch (Exception e)
			{
				e.printStackTrace();
				String error = JsonBuilder.object().appendField("error", "Invalid JSON format").build();
				sendResponse(exchange, 400, error);
			}
		}
	}

	private static class ItemListHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				String error = JsonBuilder.object().appendField("error", "Unauthorized").build();
				sendResponse(exchange, 403, error);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				String error = JsonBuilder.object().appendField("error", "Method Not Allowed").build();
				sendResponse(exchange, 405, error);
				return;
			}

			exchange.getResponseHeaders().set("Content-Type", "application/json");

			try
			{
				// Construindo JSON array com JsonBuilder
				JsonBuilder jsonBuilder = JsonBuilder.object(); // vamos usar só para criar array

				JsonBuilder.JsonArrayBuilder itemsArray = new JsonBuilder.JsonArrayBuilder();

				// Usar stream sequencial (não paralelo) para garantir ordem estável
				Arrays.stream(ItemData.getInstance().getAllItems()).filter(item -> item != null && item.getName() != null).forEach(item ->
				{
					JsonBuilder itemJson = JsonBuilder.object().appendField("id", item.getId()).appendField("weight", item.getWeight()).appendField("name", item.getName()).appendField("type", getItemCategory(item)).appendField("icon", item.getIcon() != null ? item.getIcon() : "default.png");

					itemsArray.addObject(itemJson);
				});

				// O JsonBuilder do objeto principal não cria um array, então vamos só usar o
				// array:
				String responseJson = itemsArray.build();

				exchange.sendResponseHeaders(200, 0);
				try (OutputStream os = exchange.getResponseBody(); OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8); BufferedWriter writer = new BufferedWriter(osw))
				{
					writer.write(responseJson);
				}

			}
			catch (Exception e)
			{
				e.printStackTrace();
				sendResponse(exchange, 500, "{\"error\":\"Server error\"}");
			}
		}

		private String getItemCategory(ItemTemplate item)
		{
			if (item instanceof Weapon)
				return "Weapon";
			if (item instanceof Armor)
				return "Armor";
			if (item instanceof EtcItem)
				return "EtcItem";
			return "Unknown";
		}
	}

	private static class ItemExtraHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				String error = JsonBuilder.object().appendField("error", "Unauthorized").build();
				sendResponse(exchange, 403, error);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				String error = JsonBuilder.object().appendField("error", "Method Not Allowed").build();
				sendResponse(exchange, 405, error);
				return;
			}

			exchange.getResponseHeaders().set("Content-Type", "application/json");

			try
			{
				exchange.sendResponseHeaders(200, 0);

				try (OutputStream os = exchange.getResponseBody(); OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8); BufferedWriter writer = new BufferedWriter(osw))
				{

					JsonBuilder json = JsonBuilder.object();

					Arrays.stream(ItemData.getInstance().getAllItems()).parallel().filter(Objects::nonNull).forEach(item ->
					{
						JsonBuilder itemJson = JsonBuilder.object().appendField("weight", item.getWeight()).appendField("price", item.getReferencePrice()).appendField("crystal_type", item.getCrystalType() != null ? item.getCrystalType().toString() : "none").appendField("crystal_count", item.getCrystalCount()).appendField("is_tradable", item.isTradeable()).appendField("is_dropable", item.isDropable()).appendField("is_destroyable", item.isDestroyable())
								.appendField("is_sellable", item.isSellable()).appendField("default_action", item.getDefaultAction() != null ? item.getDefaultAction().toString() : "");

						// Skills
						if (item.getAllSkills() != null && !item.getAllSkills().isEmpty())
						{
							JsonBuilder.JsonArrayBuilder skillsArray = new JsonBuilder.JsonArrayBuilder();
							for (SkillHolder skillHolder : item.getAllSkills())
							{
								Skill skill = SkillData.getInstance().getSkill(skillHolder.getSkillId(), skillHolder.getSkillLevel());
								JsonBuilder skillJson = JsonBuilder.object().appendField("id", skillHolder.getSkillId()).appendField("level", skillHolder.getSkillLevel());
								if (skill != null)
								{
									skillJson.appendField("name", skill.getName());
								}
								skillsArray.addObject(skillJson);
							}
							itemJson.appendArrayField("item_skills", skillsArray);
						}

						// EtcItem extras
						if (item instanceof EtcItem)
						{
							EtcItem etc = (EtcItem)item;
							itemJson.appendField("etc_type", etc.getItemType() != null ? etc.getItemType().name() : "Unknown").appendField("handler", etc.getHandlerName() != null ? etc.getHandlerName() : "").appendField("infinite", etc.isInfinite()).appendField("extractableMin", etc.getExtractableCountMin()).appendField("extractableMax", etc.getExtractableCountMax());

							JsonBuilder.JsonArrayBuilder capsuleArray = new JsonBuilder.JsonArrayBuilder();
							for (ExtractableProduct prod : etc.getExtractableItems())
							{
								ItemTemplate capsuledItem = ItemData.getInstance().getTemplate(prod.getId());
								String capsuledName = (capsuledItem != null && capsuledItem.getName() != null) ? capsuledItem.getName() : "Unknown";

								JsonBuilder capsuleJson = JsonBuilder.object().appendField("id", prod.getId()).appendField("name", capsuledName).appendField("min", prod.getMin()).appendField("max", prod.getMax()).appendField("chance", prod.getChance());

								capsuleArray.addObject(capsuleJson);
							}
							itemJson.appendArrayField("capsuled_items", capsuleArray);
						}

						synchronized (json)
						{
							json.appendObjectField(String.valueOf(item.getId()), itemJson);
						}
					});

					writer.write(json.build());
				}

			}
			catch (Exception e)
			{
				e.printStackTrace();
				sendResponse(exchange, 500, "{ \"error\": \"Server error\" }");
			}
		}
	}

	private static class InventoryAndPaperdollHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				String error = JsonBuilder.object().appendField("error", "Unauthorized").build();
				sendResponse(exchange, 403, error);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				String error = JsonBuilder.object().appendField("error", "Method Not Allowed").build();
				sendResponse(exchange, 405, error);
				return;
			}

			String[] parts = exchange.getRequestURI().getPath().split("/");
			if (parts.length < 5)
			{
				String error = JsonBuilder.object().appendField("error", "Player name missing in path.").build();
				sendResponse(exchange, 400, error);
				return;
			}

			String playerName = parts[4];
			Player player = World.getInstance().getPlayer(playerName);
			boolean offlineLoaded = false;

			if (player == null)
			{
				int objectId = CharInfoTable.getInstance().getIdByName(playerName);
				if (objectId > 0)
				{
					player = Player.load(objectId);
					offlineLoaded = true;
				}
				else
				{
					String error = JsonBuilder.object().appendField("error", "Player not found.").build();
					sendResponse(exchange, 404, error);
					return;
				}
			}

			JsonBuilder jsonBuilder = JsonBuilder.object();

			JsonBuilder playerData = JsonBuilder.object().appendField("playerName", playerName).appendField("playerTitle", player.getTitle()).appendField("level", player.getLevel()).appendField("exp", player.getExp()).appendField("sp", player.getSp()).appendField("hp", player.getCurrentHp()).appendField("maxhp", player.getMaxHp()).appendField("mp", player.getCurrentMp()).appendField("maxmp", player.getMaxMp()).appendField("cp", player.getCurrentCp()).appendField("maxcp", player.getMaxCp())
					.appendField("race", player.getRace().toString()).appendField("class", Optional.ofNullable(ClassListData.getInstance().getClassList().get(player.getPlayerClass())).map(ClassInfoHolder::getClassName).orElse("Unknown")).appendField("online", player.isOnline()).appendField("noble", player.isNoble());

			JsonBuilder.JsonArrayBuilder inventoryArray = new JsonBuilder.JsonArrayBuilder();
			for (Item item : player.getInventory().getItems())
			{
				if (!item.isEquipped())
				{
					inventoryArray.addObject(JsonBuilder.object().appendField("itemId", item.getId()).appendField("itemName", item.getItemName()).appendField("itemCount", item.getCount()).appendField("itemIcon", item.getTemplate().getIcon()));
				}
			}

			JsonBuilder.JsonArrayBuilder paperdollArray = new JsonBuilder.JsonArrayBuilder();
			for (Item item : player.getInventory().getItems())
			{
				if (item != null && item.getItemLocation() == ItemLocation.PAPERDOLL)
				{
					paperdollArray.addObject(JsonBuilder.object().appendField("itemId", item.getId()).appendField("itemName", item.getItemName()).appendField("itemCount", item.getCount()).appendField("itemIcon", item.getTemplate().getIcon()));
				}
			}

			JsonBuilder responseJson = JsonBuilder.object().appendObjectField("playerdata", playerData).appendArrayField("inventory", inventoryArray).appendArrayField("paperdoll", paperdollArray);

			sendResponse(exchange, 200, responseJson.build());

			if (offlineLoaded)
			{
				player.deleteMe();
			}
		}
	}

	private static class WarehouseHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				String error = JsonBuilder.object().appendField("error", "Unauthorized").build();
				sendResponse(exchange, 403, error);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				String error = JsonBuilder.object().appendField("error", "Method Not Allowed").build();
				sendResponse(exchange, 405, error);
				return;
			}

			String[] parts = exchange.getRequestURI().getPath().split("/");

			if (parts.length < 5)
			{
				String error = JsonBuilder.object().appendField("error", "Player name is missing path.").build();
				sendResponse(exchange, 400, error);
				return;
			}

			String playerName = parts[4];
			Player player = World.getInstance().getPlayer(playerName);
			if (player == null)
			{
				String error = JsonBuilder.object().appendField("error", "Player not found.").build();
				sendResponse(exchange, 404, error);
				return;
			}

			// Acessa os itens no armazém do jogador
			Collection<Item> items = player.getWarehouse().getItems();
			JsonBuilder.JsonArrayBuilder array = new JsonBuilder.JsonArrayBuilder();

			for (Item item : items)
			{
				JsonBuilder itemJson = JsonBuilder.object().appendField("itemId", item.getId()).appendField("itemName", item.getItemName()).appendField("itemCount", item.getCount()).appendField("itemIcon", item.getTemplate().getIcon());
				array.addObject(itemJson);
			}

			sendResponse(exchange, 200, array.build());
		}
	}

	private static class SkillListHandler implements HttpHandler
	{
		private static final int PAGE_SIZE = 9000099; // Número de skills por página

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				String error = JsonBuilder.object().appendField("error", "Unauthorized").build();
				sendResponse(exchange, 403, error);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("GET"))
			{
				String error = JsonBuilder.object().appendField("error", "Method Not Allowed").build();
				sendResponse(exchange, 405, error);
				return;
			}

			// Pegando o número da página da requisição (caso não venha, assumimos página 1)
			String query = exchange.getRequestURI().getQuery();
			int pageNumber = 1;
			if (query != null && query.contains("page="))
			{
				try
				{
					pageNumber = Integer.parseInt(query.split("=")[1]);
				}
				catch (NumberFormatException e)
				{
					pageNumber = 1;
				}
			}

			int startSkillId = (pageNumber - 1) * PAGE_SIZE + 1;
			int endSkillId = startSkillId + PAGE_SIZE - 1;

			JsonBuilder.JsonArrayBuilder skillsArray = new JsonBuilder.JsonArrayBuilder();
			boolean skillsAvailable = false;

			for (int skillId = startSkillId; skillId <= endSkillId; skillId++)
			{
				int maxLevel = SkillData.getInstance().getMaxLevel(skillId);
				if (maxLevel == 0)
					continue;

				for (int level = 1; level <= maxLevel; level++)
				{
					Skill skill = SkillData.getInstance().getSkill(skillId, level);
					if (skill == null)
						break;

					JsonBuilder skillJson = JsonBuilder.object().appendField("skillId", skillId).appendField("level", level).appendField("type", String.valueOf(skill.getTargetType())).appendField("operatorType", skill.getOperateType().name()).appendField("itemConsumeId", skill.getItemConsumeId()).appendField("itemConsumeCount", skill.getItemConsumeCount()).appendField("toLevel", maxLevel).appendField("name", skill.getName()).appendField("icon", skill.getIcon() != null ? skill.getIcon() : "default_icon.png");

					skillsArray.addObject(skillJson);
					skillsAvailable = true;
				}
			}

			if (!skillsAvailable)
			{
				String error = JsonBuilder.object().appendField("error", "No more skills available.").build();
				sendResponse(exchange, 404, error);
			}
			else
			{
				sendResponse(exchange, 200, skillsArray.build());
			}
		}
	}

	public class RaidBossListHandler implements HttpHandler
	{
		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				String error = JsonBuilder.object().appendField("error", "Unauthorized").build();
				sendResponse(exchange, 403, error);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("GET"))
			{
				String error = JsonBuilder.object().appendField("error", "Method Not Allowed").build();
				sendResponse(exchange, 405, error);
				return;
			}

			try
			{
				List<RaidBoss> raidBosses = new ArrayList<>();
				List<RaidBoss> grandBosses = new ArrayList<>();
				List<RaidBoss> liveBosses = new ArrayList<>();

				Collection<NpcTemplate> templates = NpcData.getInstance().getTemplates(npc -> npc != null && ("RaidBoss".equalsIgnoreCase(npc.getType()) || "GrandBoss".equalsIgnoreCase(npc.getType()) || "Monster".equalsIgnoreCase(npc.getType())));

				for (NpcTemplate npcTemplate : templates)
				{
					final int npcId = npcTemplate.getId();
					final String type = npcTemplate.getType();
					final int level = npcTemplate.getLevel();
					final String name = npcTemplate.getName();

					final List<NpcSpawnTemplate> spawns = SpawnData.getInstance().getNpcSpawns(npc -> npc.getId() == npcId && npc.hasDBSave());
					boolean hasDBSave = spawns.stream().anyMatch(NpcSpawnTemplate::hasDBSave);

					RaidBossStatus status = DBSpawnManager.getInstance().getStatus(npcId);
					boolean alive = (status == RaidBossStatus.ALIVE || status == RaidBossStatus.COMBAT);

					if ("GrandBoss".equalsIgnoreCase(type))
					{
						StatSet grandBossStats = GrandBossManager.getInstance().getStatSet(npcId);
						boolean isDummy = (grandBossStats == null);

						RaidBoss boss = new RaidBoss(npcId, name, alive, status, level, "GrandBoss");
						boss.setDummy(isDummy);

						if (!isDummy && !alive)
						{
							long respawnTime = grandBossStats.getLong("respawn_time", 0);
							if (respawnTime > System.currentTimeMillis())
							{
								boss.setRespawnTime(respawnTime);
							}
						}

						if (isDummy)
						{
							liveBosses.add(boss);
						}
						else
						{
							grandBosses.add(boss);
						}

						continue;
					}

					if ("RaidBoss".equalsIgnoreCase(type))
					{
						RaidBoss boss = new RaidBoss(npcId, name, alive, status, level, "RaidBoss");
						if (hasDBSave || alive)
						{
							raidBosses.add(boss);
						}
						else
						{
							liveBosses.add(boss);
						}
						continue;
					}

					if ("Monster".equalsIgnoreCase(type) && hasDBSave)
					{
						RaidBoss boss = new RaidBoss(npcId, name, alive, status, level, "Monster");
						raidBosses.add(boss);
					}
				}

				JsonBuilder responseJson = JsonBuilder.object().appendArrayField("raidBosses", toJsonArray(raidBosses)).appendArrayField("grandBosses", toJsonArray(grandBosses)).appendArrayField("bosses", toJsonArray(liveBosses));

				sendResponse(exchange, 200, responseJson.build());
			}
			catch (Exception e)
			{
				e.printStackTrace();
				String error = JsonBuilder.object().appendField("error", "Server error").build();
				sendResponse(exchange, 500, error);
			}
		}

		private JsonBuilder.JsonArrayBuilder toJsonArray(List<RaidBoss> bosses)
		{
			JsonBuilder.JsonArrayBuilder array = new JsonBuilder.JsonArrayBuilder();
			for (RaidBoss boss : bosses)
			{
				JsonBuilder obj = JsonBuilder.object().appendField("npcId", boss.getNpcId()).appendField("name", boss.getName()).appendField("status", boss.isAlive() ? (boss.getStatus() == RaidBossStatus.COMBAT ? "Vivo (Em combate)" : "Vivo") : "Morto").appendField("level", boss.getLevel()).appendField("type", boss.getType());

				if (boss.isDummy())
				{
					obj.appendField("dummy", true);
				}

				if (!boss.isAlive() && boss.getRespawnTime() > 0)
				{
					obj.appendField("respawnAt", boss.getRespawnTime());
				}

				array.addObject(obj);
			}
			return array;
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

		public int getNpcId()
		{
			return npcId;
		}

		public String getName()
		{
			return name;
		}

		public boolean isAlive()
		{
			return alive;
		}

		public RaidBossStatus getStatus()
		{
			return status;
		}

		public int getLevel()
		{
			return level;
		}

		public String getType()
		{
			return type;
		}

		public void setType(String type)
		{
			this.type = type;
		}

		public long getRespawnTime()
		{
			return respawnTime;
		}

		public void setRespawnTime(long respawnTime)
		{
			this.respawnTime = respawnTime;
		}

		public boolean isDummy()
		{
			return dummy;
		}

		public void setDummy(boolean dummy)
		{
			this.dummy = dummy;
		}
	}

	private static class SkillTreeHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				sendResponse(exchange, 403, JsonBuilder.object().appendField("error", "Unauthorized").build());
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("GET"))
			{
				sendResponse(exchange, 405, JsonBuilder.object().appendField("error", "Method Not Allowed").build());
				return;
			}

			exchange.getResponseHeaders().set("Content-Type", "application/json");

			try
			{
				exchange.sendResponseHeaders(200, 0);
				try (OutputStream os = exchange.getResponseBody(); OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8); BufferedWriter writer = new BufferedWriter(osw))
				{

					JsonBuilder.JsonArrayBuilder skillArray = new JsonBuilder.JsonArrayBuilder();

					for (PlayerClass playerClass : PlayerClass.values())
					{
						Map<Long,SkillLearn> skillsOfClass = SkillTreeData.getInstance().getCompleteClassSkillTree(playerClass);
						if (skillsOfClass == null)
							continue;

						for (SkillLearn skill : skillsOfClass.values())
						{
							skillArray.addObject(buildSkillJson(playerClass, skill));
						}
					}

					writer.write(skillArray.build());
				}
			}
			catch (Exception e)
			{
				e.printStackTrace();
				sendResponse(exchange, 500, JsonBuilder.object().appendField("error", "Server error").build());
			}
		}

		private JsonBuilder buildSkillJson(PlayerClass clazz, SkillLearn skill)
		{
			ClassInfoHolder classInfo = ClassListData.getInstance().getClass(clazz);
			String className = (classInfo != null) ? classInfo.getClassName() : clazz.name();

			JsonBuilder.JsonArrayBuilder requiredItemsArray = new JsonBuilder.JsonArrayBuilder();
			List<List<ItemHolder>> alternatives = skill.getRequiredItems();
			for (List<ItemHolder> alt : alternatives)
			{
				JsonBuilder.JsonArrayBuilder altArray = new JsonBuilder.JsonArrayBuilder();
				for (ItemHolder item : alt)
				{
					altArray.addObject(JsonBuilder.object().appendField("id", item.getId()).appendField("count", item.getCount()));
				}
				requiredItemsArray.addArray(altArray);
			}

			return JsonBuilder.object().appendField("classId", clazz.getId()).appendField("classEnum", clazz.name()).appendField("className", className).appendField("raceEnum", clazz.getRace().name()).appendField("raceName", StringUtil.enumToString(clazz.getRace())).appendField("skillId", skill.getSkillId()).appendField("skillLevel", skill.getSkillLevel()).appendField("requiredLevel", skill.getGetLevel()).appendField("sp", skill.getLevelUpSp()).appendArrayField("requiredItems",
					requiredItemsArray);
		}
	}

	private static class RegisterHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				sendResponse(exchange, 403, JsonBuilder.object().appendField("error", "Unauthorized").build());
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				sendResponse(exchange, 405, JsonBuilder.object().appendField("error", "Method Not Allowed").build());
				return;
			}

			try (InputStream is = exchange.getRequestBody())
			{
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

				Map<String,Object> data;
				try
				{
					data = JsonBuilder.parseJsonObject(body);
				}
				catch (Exception e)
				{
					sendResponse(exchange, 400, JsonBuilder.object().appendField("error", "Invalid JSON format").build());
					return;
				}

				String login = null;
				String password = null;
				if (data.containsKey("login"))
				{
					Object objLogin = data.get("login");
					if (objLogin instanceof String)
					{
						login = ((String)objLogin).toLowerCase();
					}
				}
				if (data.containsKey("password"))
				{
					Object objPassword = data.get("password");
					if (objPassword instanceof String)
					{
						password = (String)objPassword;
					}
				}

				if (login == null || password == null)
				{
					sendResponse(exchange, 400, JsonBuilder.object().appendField("error", "Missing login or password").build());
					return;
				}

				String encryptedPassword;
				try
				{
					encryptedPassword = encryptPasswordSHA(password);
				}
				catch (Exception e)
				{
					sendResponse(exchange, 500, JsonBuilder.object().appendField("error", "Server error").build());
					return;
				}

				try (Connection con = DatabaseFactory.getConnection(); PreparedStatement checkStmt = con.prepareStatement("SELECT COUNT(*) FROM accounts WHERE login = ?"))
				{
					checkStmt.setString(1, login);
					ResultSet rs = checkStmt.executeQuery();

					if (rs.next() && rs.getInt(1) > 0)
					{
						sendResponse(exchange, 409, JsonBuilder.object().appendField("error", "User already exists").build());
						return;
					}

					try (PreparedStatement stmt = con.prepareStatement("INSERT INTO accounts (login, password, accessLevel) VALUES (?, ?, 0)"))
					{
						stmt.setString(1, login);
						stmt.setString(2, encryptedPassword);
						stmt.executeUpdate();
					}

					sendResponse(exchange, 201, JsonBuilder.object().appendField("status", "Account created").build());

				}
				catch (SQLException e)
				{
					e.printStackTrace();
					sendResponse(exchange, 500, JsonBuilder.object().appendField("error", "Database error").build());
				}

			}
			catch (Exception e)
			{
				e.printStackTrace();
				sendResponse(exchange, 500, JsonBuilder.object().appendField("error", "Server error").build());
			}
		}

		private String encryptPasswordSHA(String password) throws Exception
		{
			MessageDigest md = MessageDigest.getInstance("SHA");
			byte[] hashedBytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(hashedBytes);
		}
	}

	private static class LoginHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				sendResponse(exchange, 403, JsonBuilder.object().appendField("error", "Unauthorized").build());
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				sendResponse(exchange, 405, JsonBuilder.object().appendField("error", "Method Not Allowed").build());
				return;
			}

			try (InputStream is = exchange.getRequestBody())
			{
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

				Map<String,Object> data;
				try
				{
					data = JsonBuilder.parseJsonObject(body);
				}
				catch (Exception e)
				{
					sendResponse(exchange, 400, JsonBuilder.object().appendField("error", "Invalid JSON format").build());
					return;
				}

				String login = null;
				String password = null;

				if (data.containsKey("login"))
				{
					Object objLogin = data.get("login");
					if (objLogin instanceof String)
					{
						login = ((String)objLogin).toLowerCase();
					}
				}

				if (data.containsKey("password"))
				{
					Object objPassword = data.get("password");
					if (objPassword instanceof String)
					{
						password = (String)objPassword;
					}
				}

				if (login == null || password == null)
				{
					sendResponse(exchange, 400, JsonBuilder.object().appendField("error", "Missing login or password").build());
					return;
				}

				try (Connection con = DatabaseFactory.getConnection(); PreparedStatement stmt = con.prepareStatement("SELECT password FROM accounts WHERE login = ?"))
				{
					stmt.setString(1, login);
					ResultSet rs = stmt.executeQuery();

					if (!rs.next())
					{
						sendResponse(exchange, 401, JsonBuilder.object().appendField("error", "Invalid login or password").build());
						return;
					}

					String storedPassword = rs.getString("password");
					if (!storedPassword.equals(encryptPasswordSHA(password)))
					{
						sendResponse(exchange, 401, JsonBuilder.object().appendField("error", "Invalid login or password").build());
						return;
					}
				}
				catch (Exception e)
				{
					e.printStackTrace();
					sendResponse(exchange, 500, JsonBuilder.object().appendField("error", "Database error").build());
					return;
				}

				sendResponse(exchange, 200, JsonBuilder.object().appendField("status", "Login successful").build());

			}
			catch (Exception e)
			{
				e.printStackTrace();
				sendResponse(exchange, 500, JsonBuilder.object().appendField("error", "Server error").build());
			}
		}

		private String encryptPasswordSHA(String password) throws Exception
		{
			MessageDigest md = MessageDigest.getInstance("SHA");
			byte[] hashedBytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(hashedBytes);
		}
	}

	private static class ChangePasswordHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				sendResponse(exchange, 403, JsonBuilder.object().appendField("error", "Unauthorized").build());
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				sendResponse(exchange, 405, JsonBuilder.object().appendField("error", "Method Not Allowed").build());
				return;
			}

			try (InputStream is = exchange.getRequestBody())
			{
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

				Map<String,Object> data;
				try
				{
					data = JsonBuilder.parseJsonObject(body);
				}
				catch (Exception e)
				{
					sendResponse(exchange, 400, JsonBuilder.object().appendField("error", "Invalid JSON format").build());
					return;
				}

				String login = null;
				String oldPassword = null;
				String newPassword = null;

				if (data.containsKey("login"))
				{
					Object objLogin = data.get("login");
					if (objLogin instanceof String)
					{
						login = ((String)objLogin).toLowerCase();
					}
				}
				if (data.containsKey("oldPassword"))
				{
					Object objOld = data.get("oldPassword");
					if (objOld instanceof String)
					{
						oldPassword = (String)objOld;
					}
				}
				if (data.containsKey("newPassword"))
				{
					Object objNew = data.get("newPassword");
					if (objNew instanceof String)
					{
						newPassword = (String)objNew;
					}
				}

				if (login == null || oldPassword == null || newPassword == null)
				{
					sendResponse(exchange, 400, JsonBuilder.object().appendField("error", "Missing fields").build());
					return;
				}

				try (Connection con = DatabaseFactory.getConnection(); PreparedStatement stmt = con.prepareStatement("SELECT password FROM accounts WHERE login = ?"))
				{
					stmt.setString(1, login);
					ResultSet rs = stmt.executeQuery();

					if (!rs.next())
					{
						sendResponse(exchange, 401, JsonBuilder.object().appendField("error", "User not found").build());
						return;
					}

					String storedPassword = rs.getString("password");
					if (!storedPassword.equals(encryptPasswordSHA(oldPassword)))
					{
						sendResponse(exchange, 401, JsonBuilder.object().appendField("error", "Invalid old password").build());
						return;
					}

					String encryptedNewPassword = encryptPasswordSHA(newPassword);
					try (PreparedStatement updateStmt = con.prepareStatement("UPDATE accounts SET password = ? WHERE login = ?"))
					{
						updateStmt.setString(1, encryptedNewPassword);
						updateStmt.setString(2, login);
						updateStmt.executeUpdate();
					}
				}
				catch (Exception e)
				{
					e.printStackTrace();
					sendResponse(exchange, 500, JsonBuilder.object().appendField("error", "Database error").build());
					return;
				}

				sendResponse(exchange, 200, JsonBuilder.object().appendField("status", "Password changed successfully").build());

			}
			catch (Exception e)
			{
				e.printStackTrace();
				sendResponse(exchange, 500, JsonBuilder.object().appendField("error", "Server error").build());
			}
		}

		private String encryptPasswordSHA(String password) throws Exception
		{
			MessageDigest md = MessageDigest.getInstance("SHA");
			byte[] hashedBytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(hashedBytes);
		}
	}

	private static class ShutdownGameHandler implements HttpHandler
	{

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				sendResponse(exchange, 403, JsonBuilder.object().appendField("error", "Unauthorized").build());
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
				sendResponse(exchange, 405, JsonBuilder.object().appendField("error", "Method Not Allowed").build());
				return;
			}

			int delay = 10;
			boolean restart = false;
			boolean abort = false;

			try (InputStream is = exchange.getRequestBody())
			{
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

				Map<String,Object> data;
				try
				{
					data = JsonBuilder.parseJsonObject(body);
				}
				catch (Exception e)
				{
					sendResponse(exchange, 400, JsonBuilder.object().appendField("error", "Invalid JSON format").build());
					return;
				}

				if (data.containsKey("delay"))
				{
					Object obj = data.get("delay");
					if (obj instanceof Number)
					{
						delay = ((Number)obj).intValue();
					}
					else
						if (obj instanceof String)
						{
							try
							{
								delay = Integer.parseInt((String)obj);
							}
							catch (NumberFormatException ignored)
							{
							}
						}
				}

				if (data.containsKey("restart"))
				{
					Object obj = data.get("restart");
					if (obj instanceof Boolean)
					{
						restart = (Boolean)obj;
					}
					else
						if (obj instanceof String)
						{
							restart = ((String)obj).equalsIgnoreCase("true");
						}
				}

				if (data.containsKey("abort"))
				{
					Object obj = data.get("abort");
					if (obj instanceof Boolean)
					{
						abort = (Boolean)obj;
					}
					else
						if (obj instanceof String)
						{
							abort = ((String)obj).equalsIgnoreCase("true");
						}
				}
			}
			catch (Exception e)
			{
				sendResponse(exchange, 400, JsonBuilder.object().appendField("error", "Invalid request body").build());
				return;
			}

			if (abort)
			{
				Shutdown.getInstance().abort(null);
				sendResponse(exchange, 200, JsonBuilder.object().appendField("status", "Shutdown aborted").build());
				return;
			}

			Shutdown.getInstance().startShutdown(null, delay, restart);
			String msg = restart ? "Restarting" : "Shutting down";

			sendResponse(exchange, 200, JsonBuilder.object().appendField("status", msg + " in " + delay + " seconds").build());
		}
	}

	/**
	 * Handler HTTP para verificar o status do servidor de login.
	 * Retorna "online" ou "offline" dependendo da disponibilidade do servidor.
	 */
	public class LoginServerStatusHandler implements HttpHandler
	{
		private static final String LOGIN_SERVER_HOST = "127.0.0.1";
		private static final int LOGIN_SERVER_PORT = 9014;

		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			if (!ApiServer.isAuthorized(exchange))
			{
				ApiServer.sendResponse(exchange, 403, JsonBuilder.object().appendField("error", "Unauthorized").build());
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("GET"))
			{
				ApiServer.sendResponse(exchange, 405, JsonBuilder.object().appendField("error", "Method Not Allowed").build());
				return;
			}

			boolean online = isLoginServerOnline();
			String status = online ? "online" : "offline";

			ApiServer.sendResponse(exchange, 200, JsonBuilder.object().appendField("status", status).build());
		}

		/**
		 * Tenta abrir uma conexão TCP com o servidor de login para verificar se está
		 * online.
		 *
		 * @return true se o servidor responder dentro do timeout, false caso contrário.
		 */
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

	public static void main(String[] args)
	{
		new ApiServer();
	}
}
