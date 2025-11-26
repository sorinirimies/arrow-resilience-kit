# Git Remotes Setup - Dual Push (GitHub + Gitea)

This document explains the dual-remote Git setup for pushing to both GitHub and Gitea simultaneously.

## Current Configuration

```bash
$ git remote -v
github	git@github.com:sorinirimies/arrow-resilience-kit.git (fetch)
github	git@github.com:sorinirimies/arrow-resilience-kit.git (push)
origin	ssh://git@192.168.1.204:30009/sorin/arrow-resilience-kit.git (fetch)
origin	ssh://git@192.168.1.204:30009/sorin/arrow-resilience-kit.git (push)
origin	git@github.com:sorinirimies/arrow-resilience-kit.git (push)
```

## How It Works

The `origin` remote is configured with:
- **Fetch URL:** Gitea (primary source)
- **Push URLs:** Both Gitea AND GitHub (dual push)

This means:
- `git pull origin main` → pulls from Gitea
- `git push origin main` → pushes to BOTH Gitea and GitHub
- `git push github main` → pushes to GitHub only (if needed)

## Setup (How We Did It)

```bash
# 1. Add GitHub as a separate remote
git remote add github git@github.com:sorinirimies/arrow-resilience-kit.git

# 2. Add Gitea push URL to origin
git remote set-url --add --push origin ssh://git@192.168.1.204:30009/sorin/arrow-resilience-kit.git

# 3. Add GitHub push URL to origin
git remote set-url --add --push origin git@github.com:sorinirimies/arrow-resilience-kit.git
```

## Using Justfile (Recommended)

The justfile provides convenient commands:

```bash
# Push to both remotes
just push

# Pull from origin (Gitea)
just pull

# Push to GitHub only
just push-github

# Push tags to both remotes
just push-tags

# Show remotes
just remotes
```

## Manual Git Commands

### Push to Both

```bash
git push origin main
# Pushes to both Gitea and GitHub
```

### Push to One Remote Only

```bash
# Push to Gitea only
git push git@192.168.1.204:30009/sorin/arrow-resilience-kit.git main

# Push to GitHub only
git push github main
```

### Push Tags

```bash
# Push tags to both remotes
git push origin --tags
git push github --tags

# Or use justfile
just push-tags
```

## Benefits

1. **Single Command Push** - One `git push` updates both remotes
2. **Always Synced** - Both GitHub and Gitea stay in sync
3. **Backup** - Your code exists on multiple platforms
4. **Flexibility** - Can still push to one remote if needed

## Workflow Examples

### Daily Development

```bash
# Make changes
vim src/...

# Commit
git commit -am "feat: add new feature"

# Push to both remotes
just push
# or
git push origin main
```

### Creating a Release

```bash
# Use justfile release workflow (recommended)
just release 0.2.0

# Or manually
just bump 0.2.0
git push origin main
git push github main
git push origin v0.2.0
git push github v0.2.0
```

### Force Push (Use with Caution!)

```bash
# Force push to both
just push-force

# Or manually
git push --force origin main
git push --force github main
```

## Troubleshooting

### Push Fails to One Remote

If push to one remote fails, the other still succeeds. Check the error:

```bash
git push origin main
# Shows separate output for each push
```

### SSH Key Issues

Make sure SSH keys are set up for both:
- GitHub: `~/.ssh/id_rsa.pub` added to GitHub
- Gitea: Same key added to Gitea

Test connections:
```bash
ssh -T git@github.com
ssh -T git@192.168.1.204 -p 30009
```

### Different Commit History

If remotes diverge:

```bash
# Sync GitHub with Gitea (force)
just sync-github

# Or manually
git push github main --force
git push github --tags --force
```

## Comparison with tui-slider

Your `tui-slider` project uses a different approach:

**tui-slider:**
- `origin` → GitHub (fetch and push)
- `gitea` → Gitea (separate remote)
- Must push to each separately

**arrow-resilience-kit:**
- `origin` → Gitea (fetch), Both (push)
- `github` → GitHub (fetch and push)
- Single push updates both

## Migration to This Setup

If you want to use this setup in other projects:

```bash
cd your-project

# Add second push URL
git remote set-url --add --push origin YOUR_SECOND_REMOTE_URL

# Verify
git remote -v

# Test
git push origin main
```

## .git/config

The configuration in `.git/config` looks like:

```ini
[remote "origin"]
    url = ssh://git@192.168.1.204:30009/sorin/arrow-resilience-kit.git
    fetch = +refs/heads/*:refs/remotes/origin/*
    pushurl = ssh://git@192.168.1.204:30009/sorin/arrow-resilience-kit.git
    pushurl = git@github.com:sorinirimies/arrow-resilience-kit.git

[remote "github"]
    url = git@github.com:sorinirimies/arrow-resilience-kit.git
    fetch = +refs/heads/*:refs/remotes/github/*
```

## Best Practices

1. **Always verify remotes** after setup
   ```bash
   just remotes
   ```

2. **Test with dry run** first
   ```bash
   git push --dry-run origin main
   ```

3. **Keep remotes synced**
   - Push frequently to keep both updated
   - Use `just sync-github` if they diverge

4. **Use justfile commands**
   - Simpler and more reliable
   - Includes safety checks

## See Also

- **justfile** - All available git commands
- **JUSTFILE_GUIDE.md** - Complete workflow guide
- **QUICK_START.md** - Quick reference

---

**Status:** Active and working ✅  
**Last Updated:** 2025-01  
**Remotes:** GitHub + Gitea (dual push)
