package dev.perpetualyt.disasters;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class PerphetModMenu
        implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?>
    getModConfigScreenFactory() {

        return PerphetConfigScreen::new;
    }
}
