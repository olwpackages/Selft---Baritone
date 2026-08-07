package baritone;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BaritoneMod implements ModInitializer {
    public static final String MOD_ID = "baritone";
    public static final String MOD_NAME = "Baritone Reborn";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("{} core initialized", MOD_NAME);
    }
}
