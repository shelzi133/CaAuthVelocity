package z.caAuthVelocity.tiauth;

import ru.matveylegenda.tiauth.database.model.AuthUser;
import ru.matveylegenda.tiauth.velocity.api.TiAuthAPI;

import java.util.concurrent.CompletableFuture;

public class PlayerLookup {

    public CompletableFuture<AuthUser> find(String name) {
        return TiAuthAPI.getInstance().getDatabase().getAuthUserRepository().getUser(name);
    }
}
