# Troubleshooting ENAMETOOLONG Errors

## Overview

ENAMETOOLONG errors occur when a filename, path component, or symbolic link target exceeds filesystem limits:
- **NAME_MAX**: Maximum length for a single filename component (typically 255 bytes)
- **PATH_MAX**: Maximum length for a complete path (typically 4096 bytes)

## Root Cause in This Repository

The error was caused by a symbolic link with a 501-character target:
```
.perf/.shared/postfix/etc/postfix/dynamicmaps.cf -> [501 characters of file content]
```

The symlink was pointing to the actual file content instead of a file path, which exceeded NAME_MAX (255 chars).

## Solutions Implemented

### Primary Solution: Fix the Problematic Symlink ✅

**What we did:**
- Converted `.perf/.shared/postfix/etc/postfix/dynamicmaps.cf` from a symbolic link to a regular file
- File content remains identical (485 bytes)
- Only the file mode changed: 120000 (symlink) → 100644 (regular file)

**Why this works:**
- Removes the 501-character symlink target that exceeded NAME_MAX
- Keeps all file content intact
- Minimal change - only affects one file
- Platform-independent solution

**Git diff:**
```
mode change 120000 => 100644 .perf/.shared/postfix/etc/postfix/dynamicmaps.cf
```

### Secondary Solution: Update .gitignore ✅

Added `.perf/` to `.gitignore` to:
- Prevent future files in `.perf/` from being tracked
- Allow developers to have local performance testing artifacts without committing them
- Keep the directory structure while avoiding git tracking issues

## Alternative Solutions (Not Implemented)

These solutions were researched but not implemented. They are documented here for future reference:

### Option 1: Enable Git Long Paths

**Implementation:**
```bash
# System-wide (requires admin)
git config --system core.longpaths true

# User/global
git config --global core.longpaths true

# Per-repository
git config core.longpaths true
```

**Pros:**
- No file changes needed
- Allows paths up to 4096 characters

**Cons:**
- Only helps with PATH_MAX, not NAME_MAX (doesn't fix symlink target length)
- Platform-specific (mainly Windows)
- Doesn't solve the root cause in this case

### Option 2: Disable Symlinks in Git

**Implementation:**
```bash
git config core.symlinks false
```

**Pros:**
- Git treats symlinks as regular files
- No repository file changes needed

**Cons:**
- Changes git behavior globally
- May cause issues on systems that rely on symlinks
- Doesn't fix existing symlinks in the repo

### Option 3: Configure in GitHub Actions

**Implementation in `.github/workflows/maven.yml`:**
```yaml
- name: Checkout with long path support
  uses: actions/checkout@v4
  with:
    # Enable long path support (mainly for Windows)
    fetch-depth: 0
    
- name: Configure Git
  run: |
    git config core.longpaths true
    git config core.symlinks false
```

**Pros:**
- Fixes CI without changing repository files

**Cons:**
- Only fixes CI, not local development
- Doesn't address the root cause
- May not fully solve NAME_MAX issues with symlink targets

### Option 4: Exclude .perf/ Entirely

**Implementation:**
Remove all `.perf/` files from git tracking:
```bash
git rm -r --cached .perf/
echo ".perf/" >> .gitignore
git commit -m "Stop tracking .perf/ directory"
```

**Pros:**
- Complete isolation from git
- No future issues with .perf files

**Cons:**
- Loses version control of performance testing configurations
- Requires manual setup for new developers
- Rejected by project maintainer

## Verification

After implementing the fix, verify with:

```bash
# Clone the repository
git clone https://github.com/transilvlad/robin.git

# Check the file type
ls -la .perf/.shared/postfix/etc/postfix/dynamicmaps.cf
# Should show: -rw-rw-r-- (regular file, not a symlink)

# Verify content is correct
cat .perf/.shared/postfix/etc/postfix/dynamicmaps.cf
# Should show the Postfix configuration

# Check for other problematic symlinks
find . -type l -exec sh -c 'len=$(readlink "$1" | wc -c); if [ $len -gt 255 ]; then echo "$len $1"; fi' _ {} \;
# Should return nothing
```

## Prevention

To prevent similar issues in the future:

1. **Avoid using file content as symlink targets**
   - Symlink targets should be file paths, not content
   
2. **Check symlink target lengths before committing**
   ```bash
   find . -type l -exec sh -c 'len=$(readlink "$1" | wc -c); if [ $len -gt 255 ]; then echo "WARNING: $len $1"; fi' _ {} \;
   ```

3. **Use regular files for small configuration files**
   - If the file is small enough to fit in a symlink target, just use a regular file

4. **Test on different platforms**
   - Clone the repository on Linux, macOS, and Windows to catch platform-specific issues

## References

- [Git Long Path Support](https://www.shadynagy.com/solving-windows-path-length-limitations-in-git/)
- [Git Symbolic Link Handling](https://www.geeksforgeeks.org/git/how-does-git-handle-symbolic-links/)
- [Linux Filename Limits](https://man7.org/linux/man-pages/man7/path_resolution.7.html)
- Issue: https://github.com/transilvlad/robin/actions/runs/20903538578/job/60053134623

## Related Files

- `.gitignore` - Contains `.perf/` entry
- `.perf/.shared/postfix/etc/postfix/dynamicmaps.cf` - The fixed file (formerly a problematic symlink)
- `.github/workflows/maven.yml` - CI workflow that was failing
