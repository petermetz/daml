-- Copyright (c) 2019 The DAML Authors. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

{-# LANGUAGE TemplateHaskell, MultiWayIf #-}
module Main (main) where

import Control.Monad.Extra
import Control.Monad.IO.Class
import Control.Monad.Logger
import Data.Yaml
import qualified Data.List as List
import Path
import Path.IO

import qualified Data.Text as T
import qualified Data.Maybe as Maybe
import qualified System.Directory as Dir
import System.Process

import Options
import Types
import Upload
import Util

main :: IO ()
main = do
  opts@Options{..} <- parseOptions
  runLog opts $ do
      releaseDir <- parseAbsDir =<< liftIO (Dir.makeAbsolute optsReleaseDir)
      liftIO $ createDirIfMissing True releaseDir
      $logInfo "Checking if we should release"
      sdkVersion <- readVersionAt "HEAD"
      release <- isReleaseCommit
      let upload = if release then optsPerformUpload else PerformUpload False
      -- NOTE(MH): We add 100 to get version numbers for the individual
      -- components which are bigger than all version numbers we used
      -- before moving to the new daml repo.
      let compVersion = sdkVersion{versionMajor = 100 + versionMajor sdkVersion}

      artifacts :: [Artifact (Maybe ArtifactLocation)] <- decodeFileThrow optsArtifacts

      let targets = concatMap buildTargets artifacts
      $logInfo "Building all targets"
      liftIO $ callProcess "bazel" ("build" : map (T.unpack . getBazelTarget) targets)

      bazelLocations <- liftIO getBazelLocations
      $logInfo "Reading metadata from pom files"
      artifacts <- liftIO $ mapM (resolvePomData bazelLocations sdkVersion compVersion) artifacts

      files <- fmap concat $ forM artifacts $ \a -> do
          fs <- artifactFiles optsAllArtifacts a
          pure $ map (a,) fs
      mapM_ (\(_, (inp, outp)) -> copyToReleaseDir bazelLocations releaseDir inp outp) files

      let mavenUploadArtifacts = filter (\a -> getMavenUpload $ artMavenUpload a) artifacts
      uploadArtifacts <- concatMapM (mavenArtifactCoords optsAllArtifacts) mavenUploadArtifacts
      validateMavenArtifacts releaseDir uploadArtifacts

      if | getPerformUpload upload -> do
              $logInfo "Make release"
              releaseToBintray upload releaseDir (map (\(a, (_, outp)) -> (a, outp)) files)

              -- Uploading to Maven Central
              mavenUploadConfig <- mavenConfigFromEnv
              if not (null uploadArtifacts)
                  then
                    uploadToMavenCentral mavenUploadConfig releaseDir uploadArtifacts
                  else
                    $logInfo "No artifacts to upload to Maven Central"

              -- set variables for next steps in Azure pipelines
              liftIO . putStrLn $ "##vso[task.setvariable variable=has_released;isOutput=true]true"
              liftIO . putStrLn . T.unpack $ "##vso[task.setvariable variable=release_tag]" # renderVersion sdkVersion
         | optsLocallyInstallJars -> do
              let lib_jars = filter (\(mvn,_) -> (artifactType mvn == "jar" || artifactType mvn == "pom") && Maybe.isNothing (classifier mvn)) uploadArtifacts
              forM_ lib_jars $ \(mvn_coords, path) -> do
                  let args = ["install:install-file",
                              "-Dfile=" <> pathToString releaseDir <> pathToString path,
                              "-DgroupId=" <> foldr (<>) "" (List.intersperse "." $ map T.unpack $ groupId mvn_coords),
                              "-DartifactId=" <> (T.unpack $ artifactId mvn_coords),
                              "-Dversion=100.0.0",
                              "-Dpackaging=" <> (T.unpack $ artifactType mvn_coords)]
                  liftIO $ callProcess "mvn" args
         | otherwise -> $logInfo "Make dry run of release"
  where
    runLog Options{..} m0 = do
        let m = filterLogger (\_ ll -> ll >= optsLogLevel) m0
        if optsFullLogging then runStdoutLoggingT m else runFastLoggingT m
