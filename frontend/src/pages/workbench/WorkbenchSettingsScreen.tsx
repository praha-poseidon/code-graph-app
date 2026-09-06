import { useState } from 'react'
import { ChevronLeft, FolderGit2, ListTodo, ServerCog } from 'lucide-react'
import ProjectsPage from '../settings/ProjectsPage'
import TaskCenterPage from '../settings/TaskCenterPage'
import WorkersPage from '../settings/WorkersPage'
import { cn } from '../../lib/utils'
import type { SettingsTab } from './workbench-constants'
import type { WorkbenchController } from './state/useWorkbenchState'

export default function WorkbenchSettingsScreen({ controller }: { controller: WorkbenchController }) {
  const [tab, setTab] = useState<SettingsTab>('projects')
  const [taskRepositoryId, setTaskRepositoryId] = useState<number | null>(null)

  const openRepositoryTasks = (repositoryId: number) => {
    setTaskRepositoryId(repositoryId)
    setTab('tasks')
  }

  return (
    <div className="settings-theme flex h-full flex-col overflow-hidden bg-[#07080f] text-[#f7f2ff]">
      <header className="flex h-16 shrink-0 items-center gap-3 border-b border-white/[0.08] px-5">
        <button onClick={() => controller.setMode('graph')} title="返回工作台" className="grid h-9 w-9 place-items-center rounded-lg text-[#9d97b6] transition hover:bg-white/[0.06] hover:text-white">
          <ChevronLeft className="h-5 w-5" />
        </button>
        <div>
          <p className="text-sm font-semibold text-white">配置中心</p>
        </div>
      </header>
      <div className="flex min-h-0 flex-1">
        <aside className="w-56 shrink-0 border-r border-white/[0.08] bg-[#090910] p-3">
          <nav className="space-y-1">
            <NavItem active={tab === 'projects'} icon={<FolderGit2 className="h-4 w-4" />} label="仓库" description="来源与规则" onClick={() => setTab('projects')} />
            <NavItem active={tab === 'tasks'} icon={<ListTodo className="h-4 w-4" />} label="任务中心" description="队列与执行记录" onClick={() => { setTaskRepositoryId(null); setTab('tasks') }} />
            <NavItem active={tab === 'workers'} icon={<ServerCog className="h-4 w-4" />} label="Worker" description="节点与负载" onClick={() => setTab('workers')} />
          </nav>
        </aside>
        <main className="min-w-0 flex-1 overflow-y-auto">
          {tab === 'projects' && <ProjectsPage onOpenTasks={openRepositoryTasks} />}
          {tab === 'tasks' && <TaskCenterPage repositoryId={taskRepositoryId} onRepositoryChange={setTaskRepositoryId} />}
          {tab === 'workers' && <WorkersPage />}
        </main>
      </div>
    </div>
  )
}

function NavItem({ active, icon, label, description, onClick }: {
  active: boolean
  icon: React.ReactNode
  label: string
  description: string
  onClick: () => void
}) {
  return (
    <button onClick={onClick} className={cn(
      'flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left transition',
      active ? 'bg-violet-500/15 text-violet-100' : 'text-[#8f88a8] hover:bg-white/[0.04] hover:text-white',
    )}>
      <span className={cn('grid h-8 w-8 shrink-0 place-items-center rounded-lg', active ? 'bg-violet-500/20 text-violet-200' : 'bg-white/[0.035]')}>{icon}</span>
      <span className="min-w-0">
        <span className="block text-xs font-semibold">{label}</span>
        <span className="mt-0.5 block text-[10px] text-[#6f6984]">{description}</span>
      </span>
    </button>
  )
}
