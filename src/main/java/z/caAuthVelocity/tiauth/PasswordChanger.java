package z.caAuthVelocity.tiauth;

import ru.matveylegenda.tiauth.config.MainConfig;
import ru.matveylegenda.tiauth.database.repository.AuthUserRepository;
import ru.matveylegenda.tiauth.hash.Hash;
import ru.matveylegenda.tiauth.hash.HashFactory;
import ru.matveylegenda.tiauth.velocity.api.TiAuthAPI;

import java.util.concurrent.CompletableFuture;

public class PasswordChanger {

    public enum Result { OK, NOT_FOUND }

    public CompletableFuture<Result> change(String name, String password) {
        AuthUserRepository repository = TiAuthAPI.getInstance().getDatabase().getAuthUserRepository();
        return repository.getUser(name).thenCompose(user -> {
            if (user == null) {
                return CompletableFuture.completedFuture(Result.NOT_FOUND);
            }
            Hash hash = HashFactory.create(MainConfig.IMP.auth.hashAlgorithm);
            return repository.updatePassword(name, hash.hashPassword(password)).thenApply(ignored -> Result.OK);
        });
    }
}
