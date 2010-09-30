/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.seam.sidekick.shell.command;

import java.io.File;
import java.util.Map;
import java.util.Queue;

import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.jboss.seam.sidekick.shell.PromptType;
import org.jboss.seam.sidekick.shell.Shell;
import org.jboss.seam.sidekick.shell.command.parser.CommandParser;
import org.jboss.seam.sidekick.shell.command.parser.CompositeCommandParser;
import org.jboss.seam.sidekick.shell.command.parser.NamedBooleanOptionParser;
import org.jboss.seam.sidekick.shell.command.parser.NamedValueOptionParser;
import org.jboss.seam.sidekick.shell.command.parser.NamedValueVarargsOptionParser;
import org.jboss.seam.sidekick.shell.command.parser.OrderedValueOptionParser;
import org.jboss.seam.sidekick.shell.command.parser.OrderedValueVarargsOptionParser;
import org.jboss.seam.sidekick.shell.command.parser.ParseErrorParser;
import org.jboss.seam.sidekick.shell.command.parser.Tokenizer;
import org.jboss.seam.sidekick.shell.exceptions.PluginExecutionException;
import org.mvel2.util.ParseTools;

/**
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
public class ExecutionParser
{
   private final PluginRegistry registry;
   private final Instance<Execution> executionInstance;
   private final Tokenizer tokenizer;
   private final Shell shell;

   @Inject
   public ExecutionParser(PluginRegistry registry, Instance<Execution> executionInstance,
                          Tokenizer tokenizer, Shell shell)
   {
      this.registry = registry;
      this.executionInstance = executionInstance;
      this.tokenizer = tokenizer;
      this.shell = shell;
   }

   public Execution parse(final String line)
   {
      Queue<String> tokens = tokenizer.tokenize(line);

      Map<String, PluginMetadata> plugins = registry.getPlugins();
      Execution execution = executionInstance.get();
      execution.setOriginalStatement(line);
      CommandMetadata command = null;

      if (!tokens.isEmpty())
      {
         String first = tokens.remove();
         PluginMetadata plugin = plugins.get(first);

         if (plugin != null)
         {
            if (!tokens.isEmpty())
            {
               String second = tokens.peek();
               command = plugin.getCommand(second);
               if (command != null)
               {
                  tokens.remove();
               }
            }

            if (plugin.hasDefaultCommand())
            {
               command = plugin.getDefaultCommand();
            }

            if (command != null)
            {
               execution.setCommand(command);

               // parse parameters and set order / nulls for command invocation

               Object[] parameters = parseParameters(command, tokens);
               execution.setParameterArray(parameters);
            }
            else
            {
               throw new PluginExecutionException(plugin, "Missing command for plugin [" + plugin.getName()
                     + "], available commands: " + plugin.getCommands());
            }
         }
      }

      return execution;
   }

   private Object[] parseParameters(final CommandMetadata command, final Queue<String> tokens)
   {
      CommandParser commandParser = new CompositeCommandParser(new NamedBooleanOptionParser(),
            new NamedValueOptionParser(), new NamedValueVarargsOptionParser(), new OrderedValueOptionParser(),
            new OrderedValueVarargsOptionParser(), new ParseErrorParser());

      Map<OptionMetadata, Object> valueMap = commandParser.parse(command, tokens);

      Object[] parameters = new Object[command.getOptions().size()];
      for (OptionMetadata option : command.getOptions())
      {
         Object value = valueMap.get(option);
         PromptType promptType = option.getPromptType();
         String defaultValue = option.getDefaultValue();
         Class<?> optionType = option.getBoxedType();
         String optionDescriptor = option.getOptionDescriptor() + ": ";

         if ((value != null) && !value.toString().matches(promptType.getPattern()))
         {
            // make sure the current option value is OK
            shell.println("Could not parse [" + value + "]... please try again...");
            value = shell.promptCommon(optionDescriptor, promptType);
         }
         else if (option.isRequired() && (value == null) && (!option.hasDefaultValue()))
         {
            while (value == null)
            {
               if (isBooleanOption(option))
               {
                  value = shell.promptBoolean(optionDescriptor);
               }
               else if (isFileOption(optionType))
               {
                  value = shell.promptFile(optionDescriptor);
               }
               else if (!PromptType.ANY.equals(promptType))
               {
                  // make sure an omitted required option value is OK
                  value = shell.promptCommon(optionDescriptor, promptType);
               }
               else
               {
                  value = shell.prompt(optionDescriptor);
               }

               if (String.valueOf(value).trim().length() == 0)
               {
                  shell.println("The option is required to execute this command.");
                  value = null;
               }
            }
         }
         else if ((value == null) && (option.hasDefaultValue()))
         {
            value = defaultValue;
         }

         parameters[option.getIndex()] = value;
      }

      return parameters;
   }

   public boolean isFileOption(Class<?> optionType)
   {
      return File.class.isAssignableFrom(optionType);
   }

   private static boolean isBooleanOption(OptionMetadata option)
   {
      return ParseTools.unboxPrimitive(option.getType()) == boolean.class;
   }
}
