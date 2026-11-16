from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from dotenv import dotenv_values

from src.setup_env import main
from src.validation import validate_values


VALID_TOKEN = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef123456"


class ValidationTests(unittest.TestCase):
    def base(self) -> dict[str, str]:
        return {
            "TELEGRAM_BOT_TOKEN": VALID_TOKEN,
            "WEBHOOK_PORT": "8080",
            "WEBHOOK_PATH": "/telegram",
            "LOG_LEVEL": "INFO",
            "AI_PROVIDERS": "",
            "AI_PROVIDER": "",
        }

    def test_minimal_configuration_is_valid(self) -> None:
        self.assertEqual(validate_values(self.base()), [])

    def test_selected_provider_requires_credential(self) -> None:
        values = self.base() | {"AI_PROVIDERS": "openai", "AI_PROVIDER": "openai"}
        issues = validate_values(values)
        self.assertIn("OPENAI_API_KEY", {issue.field for issue in issues})

    def test_default_provider_must_be_selected(self) -> None:
        values = self.base() | {"AI_PROVIDER": "groq"}
        issues = validate_values(values)
        self.assertIn("AI_PROVIDER", {issue.field for issue in issues})

    def test_local_endpoint_must_be_http_url(self) -> None:
        values = self.base() | {
            "AI_PROVIDERS": "ollama",
            "AI_PROVIDER": "ollama",
            "OLLAMA_URL": "127.0.0.1:11434",
        }
        issues = validate_values(values)
        self.assertIn("OLLAMA_URL", {issue.field for issue in issues})

    def test_noninteractive_setup_writes_valid_env_and_preserves_custom_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            env_path.write_text("CUSTOM_FLAG=keep-me\n", encoding="utf-8")
            environment = {"TELEGRAM_BOT_TOKEN": VALID_TOKEN, "OPENAI_API_KEY": "secret-key"}
            with patch.dict("os.environ", environment, clear=False):
                result = main([
                    "--env-file", str(env_path), "--provider", "openai", "--non-interactive"
                ])
            self.assertEqual(result, 0)
            values = dotenv_values(env_path)
            self.assertEqual(values["CUSTOM_FLAG"], "keep-me")
            self.assertEqual(values["AI_PROVIDER"], "openai")
            self.assertEqual(values["OPENAI_API_KEY"], "secret-key")
            self.assertEqual(len(list(Path(directory).glob(".env.backup.*"))), 1)

    def test_invalid_noninteractive_setup_does_not_create_env(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_path = Path(directory) / ".env"
            with patch.dict("os.environ", {}, clear=True):
                result = main(["--env-file", str(env_path), "--non-interactive"])
            self.assertEqual(result, 2)
            self.assertFalse(env_path.exists())


if __name__ == "__main__":
    unittest.main()
