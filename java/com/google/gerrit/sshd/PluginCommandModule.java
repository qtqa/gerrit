// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.sshd;

import com.google.inject.binder.LinkedBindingBuilder;
import org.apache.sshd.server.command.Command;

public abstract class PluginCommandModule extends CommandModule {
  private final CommandName command;

  public PluginCommandModule(String pluginName) {
    super(/* slaveMode= */ false);
    this.command = Commands.named(pluginName);
  }

  @Override
  protected final void configure() {
    bind(Commands.key(command)).toProvider(new DispatchCommandProvider(command));
    configureCommands();
  }

  protected abstract void configureCommands();

  @Override
  protected LinkedBindingBuilder<Command> command(String subCmd) {
    return bind(Commands.key(command, subCmd));
  }

  protected void command(Class<? extends BaseCommand> clazz) {
    command(command, clazz);
  }

  protected void alias(String name, Class<? extends BaseCommand> clazz) {
    alias(command, name, clazz);
  }
}
