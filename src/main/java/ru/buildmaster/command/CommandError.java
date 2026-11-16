package ru.buildmaster.command;

/** Ошибка команды: сообщение показывается игроку. Стек не собирается. */
final class CommandError extends RuntimeException {

    CommandError(String message) {
        super(message, null, false, false);
    }
}
