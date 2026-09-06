import { useWorkbenchState } from './state/useWorkbenchState'
import WorkbenchLayout from './WorkbenchLayout'
import WorkbenchSettingsScreen from './WorkbenchSettingsScreen'

export default function WorkbenchPage() {
  const controller = useWorkbenchState()
  return controller.mode === 'graph'
    ? <WorkbenchLayout controller={controller} />
    : <WorkbenchSettingsScreen controller={controller} />
}
