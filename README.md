# 🥊 BoxTop (v3.5) — Documentación Técnica y Estado del Proyecto

> **Propósito del documento**: Proporcionar una visión completa y detallada de la arquitectura, funcionamiento, mecánicas implementadas y tareas pendientes de BoxTop para que cualquier desarrollador o agente de IA continúe el desarrollo sin fricciones.

---

## 1. 📌 ¿Qué es BoxTop?
**BoxTop** es un plugin competitivo, RPG y de moderación para servidores de Minecraft (Paper/Spigot 1.21+) centrado en:
1. **Sacos de Boxeo Interactivos (Boxing Bags)**: Entidades a las que los jugadores pueden golpear para entrenar estadísticas RPG (fuerza, velocidad de golpe, etc.), competir en tablas de clasificación (leaderboards/hologramas) y recibir recompensas/dinero.
2. **Sistema de Baneo Penitenciario ("Humiliation System")**: En lugar de simplemente expulsar o impedir la entrada a un usuario sancionado, el plugin lo convierte físicamente en un **saco de boxeo viviente** amarrado a un poste (`/boxtop ban <jugador> [tiempo] [motivo]`).
   - Los demás jugadores del servidor pueden ir al área de postes y golpear al jugador sancionado.
   - Si el jugador sancionado está **online**, se le congela la capacidad de atacar/romper/ejecutar comandos, se le ata una rienda hacia el poste, y cuando habla en el chat el plugin sustituye sus mensajes por frases de burla programadas.
   - Si el jugador sancionado está **offline**, se genera un *Dummy* fantasma con su skin/cabeza atado al poste que responde a los golpes con knockback y rebote elástico idéntico a un saco.

---

## 2. 🏛️ Arquitectura del Proyecto

```
src/main/java/fun/gexel/boxtop/
├── BoxTopPlugin.java              # Clase principal (ciclo de vida onEnable/onDisable, registro de comandos y eventos)
├── commands/
│   ├── BoxTopCommand.java         # Comando base para jugadores (/boxtop stats, top, etc.)
│   └── BoxTopAdminCommand.java    # Subcomandos administrativos (/boxtop admin create, ban, unban, reload, etc.)
├── managers/
│   ├── BagSpawnManager.java       # Spawneo, persistencia y registro de entidades saco de boxeo
│   ├── BanManager.java            # Núcleo del sistema de baneos: postes, riendas, dummy offline y física elástica
│   ├── DataManager.java           # Almacenamiento plano en YAML (bags.yml, bans.yml, players.yml)
│   ├── HologramManager.java       # Manejo de hologramas (integrado con DecentHolograms o PacketEvents/DisplayEntities)
│   ├── GlowManager.java           # Colores y brillos por Scoreboard Teams (BT_ prefix)
│   ├── StaminaManager.java        # Fatiga y cooldown por golpes consecutivos
│   ├── RewardManager.java         # Recompensas económicas / ítems por metas de golpes
│   └── SoundManager.java          # Efectos de sonido configurables por golpe o evento
└── listeners/
    ├── HitListener.java           # Intercepción de daño a sacos/jugadores baneados, cálculo de daño y físicas
    └── BanListener.java           # Restricciones a baneados (PlayerMoveEvent clamp, chat burlón, bloqueo de comandos)
```

---

## 3. ⚙️ Cómo Funciona Internamente

### A. La Rienda Elástica en Jugadores Online (Visual + Físicas)
Minecraft Spigot **no permite** usar `setLeashHolder()` directamente en jugadores (`Player`).
* **Truco Visual:**
  - Se spawnea un `Bat` (murciélago) invisible, invulnerable, mudo y sin IA justo en las coordenadas del jugador.
  - La rienda (`Leash`) se conecta entre la valla del poste (`Fence`) y el `Bat`.
  - Un task (`visualSync()`) corre a **1 tick (20 Hz)** teletransportando al murciélago exactamente a la posición del jugador, garantizando cero lag visual o jitter en la cuerda.
* **Físicas y Knockback:**
  - Un loop cada **2 ticks** (`tetherCheck()`) monitorea la distancia entre el jugador y el centro del poste.
  - **Zona libre / Knockback (0 a 2.5 bloques):** El plugin no interfiere. Permite que el knockback natural del golpe se desarrolle sin frenos bruscos.
  - **Zona elástica (2.5 a 6.0 bloques):** Se amortigua la velocidad actual del jugador multiplicándola por `0.6` y se aplica un vector hacia el centro proporcional a la distancia (`setVelocity(pull)`).
  - **Muro duro / Hard Clamp (6+ bloques):**
    - En `BanListener.java` (`PlayerMoveEvent`), cualquier intento de movimiento que proyecte al jugador a más de 6 bloques es cancelado de inmediato (`event.setTo(assigned)`), evitando desyncs con anti-cheats o tirones por ping alto.

### B. El Dummy Offline (Saco Fantasma)
- Cuando el jugador baneado sale del servidor, se crea un `Villager` persistente disfrazado con la cabeza del jugador (`PLAYER_HEAD` con `SkullMeta`).
- **Punto crítico:** Tiene `setAI(true)` pero `PotionEffect(SLOW, 255)`. Esto mantiene activo el motor de físicas de Minecraft para que `setVelocity()` funcione al recibir golpes de otros usuarios, mientras que la lentitud extrema le impide caminar solo.

### C. Sistema de "Return Damage" (Antispam / Autoclicker)
- En `HitListener.java`, si un jugador golpea un saco o dummy más rápido que un umbral (`threshold-ms`), recibe contragolpe (`RETURN_DAMAGE`) y el mensaje `"&cYour hand hurts from hitting too fast!"`.

---

## 4. 🚀 Lo que se Implementó Recientemente (v3.5)

1. **Reemplazo de Villager por Bat como Leash Holder**:
   - Se erradicó el overhead del sistema `Brain` de los aldeanos. Hitbox diminuta y libre de gravedad.
2. **Sincronización a 1 Tick (`visualSync`)**:
   - La cuerda visual se actualiza a 20 FPS, eliminando los saltos bruscos que provocaba el task de 2 ticks.
3. **Física Elástica con Zona de Amortiguación**:
   - Zona de 2.5 bloques sin interferencia para knockback fluido + atenuación suave a `0.6` en lugar del corte abrupto a `0.25`.
4. **Hard Clamp en `PlayerMoveEvent`**:
   - Límite absoluto a 6 bloques en el listener de movimiento para no depender exclusivamente de la velocidad contra anti-cheats.
5. **Configuración de "Return Damage"**:
   - Parámetros completamente personalizables en `config.yml` (`enabled`, `threshold-ms`, `damage`, `message`).
6. **Dummy con Físicas Operativas**:
   - Cambio de `setAI(false)` a `setAI(true)` para que el dummy acepte fuerzas cinéticas al ser golpeado.
7. **Limpieza en Desbaneo**:
   - Eliminación de glowing (`setGlowing(false)`), efectos de poción y remoción de los equipos de scoreboard `BT_*`.

---

## 5. 📋 Tareas Pendientes / Hoja de Ruta

### 1. Mensaje de Bienvenida / Onboarding para Administradores
- **Idea del usuario**: Al instalar el plugin por primera vez o cuando un operador entra al servidor:
  - Enviar un mensaje decorado en el chat agradeciendo la instalación.
  - Ofrecer el enlace directo al tutorial/video de configuración.
  - Generar un ítem interactivo o mapa especial ("mapa fantasma") para hacer el primer setup intuitivo y llamativo.

### 2. Estructura de Comandos Administrativos
- Asegurar que todos los subcomandos de gestión queden unificados bajo `/boxtop admin`:
  - `/boxtop admin create <nombre>`
  - `/boxtop admin remove <nombre>`
  - `/boxtop admin ban <jugador> [tiempo] [motivo]`
  - `/boxtop admin unban <jugador>`
  - `/boxtop admin setpost <id>`
  - `/boxtop admin reload`

### 3. Idioma y Formato
- **Regla inmutable del usuario**: Toda la interfaz visual, mensajes por defecto en `config.yml`, descripciones de comandos y logs en consola deben estar en **inglés**.

### 4. Casos Borde en el Sistema de Baneos
- Manejar cambios de dimensión/portales (impedir que jugadores atados puedan ingresar a portales del Nether o End).
- Asegurar que los chunks con postes asignados no se descarguen (`Chunk.setForceLoaded(true)`) mientras tengan entidades atadas.

---

## 6. 🛠️ Comandos de Compilación
```bash
# Compilar paquete sombreado (Paper 1.21+)
mvn clean package
# Ubicación del binario final:
# target/BoxTop-3.5.jar
```
