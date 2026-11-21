package com.ether4o4.odcm.agent

import org.junit.Assert.*
import org.junit.Test

class RequestRouteTest {
    @Test fun ordinaryConversationDoesNotBecomeWorkEvenWhenItMentionsAppsOrActions() {
        val messages = listOf(
            "I had a rough day", "Let's just talk", "I'm thinking about my projects",
            "Do you like music?", "That reminds me of yesterday", "Yes", "Tell me more",
            "The conversation still isn't quite there", "I want to talk about my phone",
            "Why is my phone slow?", "Tell me about this app", "How do I open Settings?",
            "Can you explain how to send a message?", "I wish this app worked better",
            "I don't want you to start working on something", "Don't open Notes",
            "Please don't open Notes", "Could you please not delete anything?",
            "If I said open Notes, what would happen?", "Imagine tapping that button",
            "\"Open Notes\" is an example command", "Send me a joke", "Write a poem",
            "My screen is cracked", "I can open up to you", "Help me understand notifications",
            "Can you open up to me?", "Let's go back to what you said", "Call me Alex",
            "/chat open Notes", "  /chat go home", "/deep think about my idea",
        )
        for (message in messages) assertFalse("Must stay conversational: $message", RequestRoute.phone(message))
    }

    @Test fun clearPhoneRequestsStillGetToolsWithoutAClassifierCall() {
        val messages = listOf(
            "Open Notes", "Can you open Settings?", "Could you please scroll down?",
            "Please go home", "Back!", "Recents", "Tap the save button", "Swipe left",
            "I want you to open Obsidian", "I'd like you to launch Termux", "Let's open Notes",
            "Hey ODCM, please open Chrome", "What is on my screen?", "What's on the screen?",
            "What’s on my screen?", "/task can you tell me what you see",
            "What can you see on my screen?", "Which app am I in?", "Show me my notifications",
            "Read the screen", "Check my messages", "Take a screenshot", "Turn off Bluetooth",
            "Enable Wi-Fi", "Turn the flashlight on", "Turn Do Not Disturb on", "Set an alarm for eight",
            "Change my brightness to half", "Copy this text", "Paste the code",
            "Send a text to Sam", "Call Mom", "Reply to the message", "Search for cats in Chrome",
            "/task do that for me", "/deep open Notes", "  /task do that",
        )
        for (message in messages) assertTrue("Must preserve phone control: $message", RequestRoute.phone(message))
    }
}
