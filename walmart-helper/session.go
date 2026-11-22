package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"time"

	walmart "github.com/eshaffer321/walmart-client-go/v2"
)

func protectedSession(path string) error {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 1<<20 {
		return errors.New("session_unavailable")
	}
	// Windows ACL support must be qualified before enabling session use there.
	if runtime.GOOS == "windows" {
		return errors.New("session_acl_unqualified")
	}
	if info.Mode().Perm()&0077 != 0 {
		return errors.New("session_permissions")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var session struct {
		Cookies map[string]struct {
			Value string `json:"value"`
		} `json:"cookies"`
	}
	if json.Unmarshal(data, &session) != nil {
		return invalid
	}
	for _, key := range []string{"CID", "SPID", "auth"} {
		if session.Cookies[key].Value == "" {
			return invalid
		}
	}
	return nil
}
func importSession(capture, destination string) error {
	if runtime.GOOS == "windows" {
		return errors.New("session_acl_unqualified")
	}
	if !filepath.IsAbs(capture) || !filepath.IsAbs(destination) {
		return invalid
	}
	info, err := os.Lstat(capture)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 1<<20 {
		return invalid
	}
	// Reviewed InitializeFromCurl tokenizes text; it never invokes a shell.
	dir := filepath.Dir(destination)
	if err = os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	stage, err := os.CreateTemp(dir, ".walmart-session-*")
	if err != nil {
		return err
	}
	name := stage.Name()
	stage.Close()
	defer os.Remove(name)
	client, err := walmart.NewWalmartClient(walmart.ClientConfig{CookieFile: name, RateLimit: time.Second, MaxRetries: -1})
	if err != nil {
		return invalid
	}
	if client.InitializeFromCurl(capture) != nil {
		return invalid
	}
	if err = os.Chmod(name, 0600); err != nil {
		return err
	}
	if protectedSession(name) != nil {
		return invalid
	}
	return os.Rename(name, destination)
}
