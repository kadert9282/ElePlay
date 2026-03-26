#!/bin/bash
# cleanup_stdlib.sh
# Запускать из корня проекта

STDLIB="app/src/main/assets/python311-stdlib"

echo "Size BEFORE cleanup:"
du -sh "$STDLIB"

# ============================================
# Удаляем то, что ТОЧНО не нужно
# ============================================

# Тесты — самое жирное (~30-40MB)
find "$STDLIB" -type d -name "test" -exec rm -rf {} + 2>/dev/null
find "$STDLIB" -type d -name "tests" -exec rm -rf {} + 2>/dev/null
find "$STDLIB" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null

# GUI/Desktop — не нужно на Android
rm -rf "$STDLIB/tkinter"
rm -rf "$STDLIB/turtle.py"
rm -rf "$STDLIB/turtledemo"
rm -rf "$STDLIB/idlelib"
rm -rf "$STDLIB/idle_test"

# Документация и примеры
rm -rf "$STDLIB/ensurepip"
rm -rf "$STDLIB/venv"
rm -rf "$STDLIB/distutils"
rm -rf "$STDLIB/lib2to3"
rm -rf "$STDLIB/pydoc_data"
rm -rf "$STDLIB/pydoc.py"
rm -rf "$STDLIB/doctest.py"
rm -rf "$STDLIB/unittest"

# Серверные/десктопные модули
rm -rf "$STDLIB/xmlrpc"
rm -rf "$STDLIB/wsgiref"
rm -rf "$STDLIB/imaplib.py"
rm -rf "$STDLIB/poplib.py"
rm -rf "$STDLIB/smtplib.py"
rm -rf "$STDLIB/smtpd.py"
rm -rf "$STDLIB/nntplib.py"
rm -rf "$STDLIB/ftplib.py"
rm -rf "$STDLIB/telnetlib.py"
rm -rf "$STDLIB/mailbox.py"
rm -rf "$STDLIB/mailcap.py"
rm -rf "$STDLIB/mimetypes.py"

# Отладка/профилирование
rm -rf "$STDLIB/pdb.py"
rm -rf "$STDLIB/profile.py"
rm -rf "$STDLIB/pstats.py"
rm -rf "$STDLIB/cProfile.py"
rm -rf "$STDLIB/trace.py"
rm -rf "$STDLIB/timeit.py"

# Компиляция/AST
rm -rf "$STDLIB/py_compile.py"
rm -rf "$STDLIB/compileall.py"
rm -rf "$STDLIB/ast.py"
rm -rf "$STDLIB/dis.py"
rm -rf "$STDLIB/opcode.py"
rm -rf "$STDLIB/symtable.py"
rm -rf "$STDLIB/tabnanny.py"
rm -rf "$STDLIB/tokenize.py"
rm -rf "$STDLIB/token.py"

# Curses
rm -rf "$STDLIB/curses"

# Multiprocessing (не работает на Android нормально)
rm -rf "$STDLIB/multiprocessing"
rm -rf "$STDLIB/concurrent"

# ctypes (если не используется yt_dlp напрямую — оставь на всякий)
# rm -rf "$STDLIB/ctypes"

# Typing extensions — часто не нужны runtime
rm -rf "$STDLIB/typing_extensions.py"

# Config parser и ini — нужны, НЕ удаляем
# Logging — нужен, НЕ удаляем
# JSON — нужен, НЕ удаляем
# HTTP/urllib — нужны для yt_dlp, НЕ удаляем
# SSL — нужен, НЕ удаляем
# subprocess — нужен, НЕ удаляем

# Удаляем .pyc если остались
find "$STDLIB" -name "*.pyc" -delete 2>/dev/null
find "$STDLIB" -name "*.pyo" -delete 2>/dev/null

# Удаляем пустые папки
find "$STDLIB" -type d -empty -delete 2>/dev/null

echo ""
echo "Size AFTER cleanup:"
du -sh "$STDLIB"