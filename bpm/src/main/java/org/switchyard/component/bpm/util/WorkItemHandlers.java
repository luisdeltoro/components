/* 
 * JBoss, Home of Professional Open Source 
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved. 
 * See the copyright.txt in the distribution for a 
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use, 
 * modify, copy, or redistribute it subject to the terms and conditions 
 * of the GNU Lesser General Public License, v. 2.1. 
 * This program is distributed in the hope that it will be useful, but WITHOUT A 
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details. 
 * You should have received a copy of the GNU Lesser General Public License, 
 * v.2.1 along with this distribution; if not, write to the Free Software 
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, 
 * MA  02110-1301, USA.
 */
package org.switchyard.component.bpm.util;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.jbpm.process.workitem.wsht.AbstractHTWorkItemHandler;
import org.jbpm.process.workitem.wsht.LocalHTWorkItemHandler;
import org.kie.runtime.KieRuntime;
import org.kie.runtime.KnowledgeRuntime;
import org.kie.runtime.process.ProcessRuntime;
import org.kie.runtime.process.WorkItemHandler;
import org.switchyard.ServiceDomain;
import org.switchyard.common.type.reflect.Construction;
import org.switchyard.component.bpm.config.model.BPMComponentImplementationModel;
import org.switchyard.component.bpm.config.model.WorkItemHandlerModel;
import org.switchyard.component.bpm.config.model.WorkItemHandlersModel;
import org.switchyard.component.bpm.service.SwitchYardServiceTaskHandler;
import org.switchyard.component.bpm.service.SwitchYardServiceWorkItemHandler;
import org.switchyard.component.common.knowledge.service.SwitchYardServiceInvoker;
import org.switchyard.exception.SwitchYardException;

/**
 * WorkItemHandler functions.
 *
 * @author David Ward &lt;<a href="mailto:dward@jboss.org">dward@jboss.org</a>&gt; &copy; 2012 Red Hat Inc.
 */
public final class WorkItemHandlers {

    private static final String HUMAN_TASK = "Human Task";

    private static final Class<?>[][] PARAMETER_TYPES = new Class<?>[][]{
        new Class<?>[]{ProcessRuntime.class},
        new Class<?>[]{KieRuntime.class},
        new Class<?>[]{KnowledgeRuntime.class},
        new Class<?>[0]
    };

    private static final Map<String, Class<? extends WorkItemHandler>> DEFAULT_HANDLERS = new HashMap<String, Class<? extends WorkItemHandler>>();
    static {
        DEFAULT_HANDLERS.put(HUMAN_TASK, LocalHTWorkItemHandler.class);
        DEFAULT_HANDLERS.put(SwitchYardServiceTaskHandler.SERVICE_TASK, SwitchYardServiceTaskHandler.class);
        DEFAULT_HANDLERS.put(SwitchYardServiceWorkItemHandler.SWITCHYARD_SERVICE, SwitchYardServiceWorkItemHandler.class);
    }

    /**
     * Registers work item handlers.
     * @param model the model
     * @param loader the class loader
     * @param runtime the process runtime
     * @param domain the service domain
     */
    public static void registerWorkItemHandlers(BPMComponentImplementationModel model, ClassLoader loader, ProcessRuntime runtime, ServiceDomain domain) {
        String tns = model.getComponent().getTargetNamespace();
        Set<String> registeredNames = new HashSet<String>();
        WorkItemHandlersModel workItemHandlersModel = model.getWorkItemHandlers();
        if (workItemHandlersModel != null) {
            for (WorkItemHandlerModel workItemHandlerModel : workItemHandlersModel.getWorkItemHandlers()) {
                @SuppressWarnings("unchecked")
                Class<? extends WorkItemHandler> workItemHandlerClass = (Class<? extends WorkItemHandler>)workItemHandlerModel.getClazz(loader);
                if (workItemHandlerClass == null) {
                    throw new SwitchYardException("Could not load workItemHandler class: " + workItemHandlerModel.getModelConfiguration().getAttribute("class"));
                }
                WorkItemHandler workItemHandler = newWorkItemHandler(workItemHandlerClass, runtime);
                String name = workItemHandlerModel.getName();
                if (workItemHandler instanceof SwitchYardServiceWorkItemHandler) {
                    SwitchYardServiceWorkItemHandler syswih = (SwitchYardServiceWorkItemHandler)workItemHandler;
                    if (name != null) {
                        syswih.setName(name);
                    } else {
                        name = syswih.getName();
                    }
                    syswih.setInvoker(new SwitchYardServiceInvoker(domain, tns));
                    syswih.setProcessRuntime(runtime);
                }
                if (name == null && workItemHandler instanceof AbstractHTWorkItemHandler) {
                    name = HUMAN_TASK;
                }
                if (name == null) {
                    throw new SwitchYardException("Could not use null name to register workItemHandler: " + workItemHandler.getClass().getName());
                }
                runtime.getWorkItemManager().registerWorkItemHandler(name, workItemHandler);
                registeredNames.add(name);
            }
        }
        for (Entry<String, Class<? extends WorkItemHandler>> entry : DEFAULT_HANDLERS.entrySet()) {
            String name = entry.getKey();
            if (!registeredNames.contains(name)) {
                WorkItemHandler defaultHandler = newWorkItemHandler(entry.getValue(), runtime);
                if (defaultHandler instanceof SwitchYardServiceWorkItemHandler) {
                    SwitchYardServiceWorkItemHandler syswih = (SwitchYardServiceWorkItemHandler)defaultHandler;
                    syswih.setName(name);
                    syswih.setInvoker(new SwitchYardServiceInvoker(domain, tns));
                    syswih.setProcessRuntime(runtime);
                }
                runtime.getWorkItemManager().registerWorkItemHandler(name, defaultHandler);
                registeredNames.add(name);
            }
        }
    }

    /**
     * Creates a new work item hander.
     * @param workItemHandlerClass the class
     * @param runtime the process runtime
     * @return the work item handler
     */
    public static WorkItemHandler newWorkItemHandler(Class<? extends WorkItemHandler> workItemHandlerClass, ProcessRuntime runtime) {
        WorkItemHandler workItemHandler = null;
        Constructor<? extends WorkItemHandler> constructor = getConstructor(workItemHandlerClass);
        Class<?>[] parameterTypes = constructor != null ? constructor.getParameterTypes() : new Class<?>[0];
        try {
            if (parameterTypes.length == 0) {
                workItemHandler = Construction.construct(workItemHandlerClass);
            } else if (parameterTypes.length == 1) {
                workItemHandler = Construction.construct(workItemHandlerClass, parameterTypes, new Object[]{runtime});
            }
        } catch (Throwable t) {
            throw new SwitchYardException("Could not instantiate workItemHandler class: " + workItemHandlerClass.getName());
        }
        return workItemHandler;
    }

    private static Constructor<? extends WorkItemHandler> getConstructor(Class<? extends WorkItemHandler> workItemHandlerClass) {
        Constructor<? extends WorkItemHandler> constructor = null;
        for (Class<?>[] parameterTypes : PARAMETER_TYPES) {
            try {
                constructor = workItemHandlerClass.getConstructor(parameterTypes);
                if (constructor != null) {
                    break;
                }
            } catch (Throwable t) {
                // keep checkstyle happy ("at least one statement")
                t.getMessage();
            }
        }
        return constructor;
    }

    private WorkItemHandlers() {}

}
