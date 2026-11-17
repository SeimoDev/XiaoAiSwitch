"""Build explicit provider-reference test data; deliberately keep each transport distinct."""


def with_registry(config):
    config["providers"] = {}
    for route in ["chat", "voice", "image", "control"]:
        normal = config.get(route)
        if normal is None:
            continue
        for mode, profile in [("default", normal), ("thinking", normal.get("thinking"))]:
            if profile is None:
                continue
            key = route + "_" + mode
            config["providers"][key] = {
                "name": key,
                "provider": profile.pop("provider", "openai_compatible"),
                "endpoint": profile.pop("endpoint", "https://chatgpt.com/backend-api/codex"),
                "token": profile.pop("token", ""),
            }
            profile["providerId"] = key
    return config
