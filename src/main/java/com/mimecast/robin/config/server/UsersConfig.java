package com.mimecast.robin.config.server;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.ConfigFoundation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Users configuration.
 *
 * <p>This class provides type safe access to user authentication configuration.
 * <p>Contains a list of users and a flag to enable/disable the user list.
 * <p>User list is ignored if Dovecot authentication is enabled.
 *
 * @see UserConfig
 * @see ServerConfig
 */
@SuppressWarnings("unchecked")
public class UsersConfig extends BasicConfig {

    /**
     * Constructs a new UsersConfig instance.
     *
     * @param map Configuration map.
     */
    public UsersConfig(Map<String, Object> map) {
        super(map);
    }

    /**
     * Checks if user list is enabled.
     * <p>This feature should be used for testing only.
     * <p>This is disabled by default for security reasons.
     *
     * @return true if user list is enabled, false otherwise.
     */
    public boolean isListEnabled() {
        return getBooleanProperty("listEnabled", false);
    }

    /**
     * Gets users list.
     *
     * @return List of UserConfig instances.
     */
    public List<UserConfig> getList() {
        List<UserConfig> users = new ArrayList<>();
        List<Map<String, String>> userList = (List<Map<String, String>>) getListProperty("list");

        if (userList != null) {
            for (Map<String, String> user : userList) {
                users.add(new UserConfig(user));
            }
        }

        return users;
    }

    /**
     * Gets user by username.
     *
     * @param find Username to find.
     * @return Optional of UserConfig.
     */
    public Optional<UserConfig> getUser(String find) {
        for (UserConfig user : getList()) {
            if (user.getName().equals(find)) {
                return Optional.of(user);
            }
        }
        return Optional.empty();
    }
}
