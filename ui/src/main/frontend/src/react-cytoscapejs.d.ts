declare module 'react-cytoscapejs' {
  import cytoscape from 'cytoscape'
  import React from 'react'

  interface CytoscapeComponentProps {
    elements: cytoscape.ElementDefinition[]
    stylesheet?: cytoscape.StylesheetCSS[]
    layout?: cytoscape.LayoutOptions
    cy?: (cy: cytoscape.Core) => void
    style?: React.CSSProperties
    className?: string
  }

  const CytoscapeComponent: React.FC<CytoscapeComponentProps>
  export default CytoscapeComponent
}
