import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../table';

function Deliveries({ rows, stack }: { rows: string[]; stack?: boolean }) {
  return (
    <Table stack={stack} data-testid="table">
      <TableHeader>
        <TableRow>
          <TableHead><input type="checkbox" aria-label="Select all" /></TableHead>
          <TableHead>Status</TableHead>
          <TableHead>Endpoint</TableHead>
          <TableHead className="hidden lg:table-cell">Created</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.length === 0 ? (
          <TableRow><TableCell colSpan={4}>Nothing here</TableCell></TableRow>
        ) : rows.map((row) => (
          <TableRow key={row}>
            <TableCell><input type="checkbox" aria-label={`Select ${row}`} /></TableCell>
            <TableCell>Success</TableCell>
            <TableCell>{row}</TableCell>
            <TableCell className="hidden lg:table-cell">2 min ago</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

const cellsOf = (text: string) => screen.getByText(text).closest('tr')!.querySelectorAll('td');

describe('Table on a phone', () => {
  it('labels every body cell with its column header', async () => {
    render(<Deliveries rows={['https://billing.example.com/hooks']} />);

    await waitFor(() => {
      const cells = cellsOf('https://billing.example.com/hooks');
      expect(cells[1]).toHaveAttribute('data-label', 'Status');
      expect(cells[2]).toHaveAttribute('data-label', 'Endpoint');
      expect(cells[3]).toHaveAttribute('data-label', 'Created');
    });
  });

  it('marks the selection column instead of labelling it', async () => {
    render(<Deliveries rows={['https://billing.example.com/hooks']} />);

    await waitFor(() => {
      const select = cellsOf('https://billing.example.com/hooks')[0];
      expect(select).toHaveAttribute('data-cell', 'select');
      expect(select).not.toHaveAttribute('data-label');
    });
  });

  it('leaves a full-width cell such as an empty state unlabelled', async () => {
    render(<Deliveries rows={[]} />);

    await waitFor(() => expect(screen.getByText('Nothing here').closest('td')).toHaveAttribute('data-cell', 'wide'));
    expect(screen.getByText('Nothing here').closest('td')).not.toHaveAttribute('data-label');
  });

  it('labels rows that arrive after the first render', async () => {
    function Growing() {
      const [rows, setRows] = useState(['first.example.com']);
      return (
        <>
          <button type="button" onClick={() => setRows((r) => [...r, 'second.example.com'])}>more</button>
          <Deliveries rows={rows} />
        </>
      );
    }
    render(<Growing />);
    screen.getByRole('button', { name: 'more' }).click();

    await waitFor(() => expect(cellsOf('second.example.com')[2]).toHaveAttribute('data-label', 'Endpoint'));
  });

  it('stacks by default, and a table can opt out', () => {
    const { unmount } = render(<Deliveries rows={['a.example.com']} />);
    expect(screen.getByTestId('table').parentElement).toHaveClass('rh-table-stack');
    unmount();

    render(<Deliveries rows={['a.example.com']} stack={false} />);
    expect(screen.getByTestId('table').parentElement).not.toHaveClass('rh-table-stack');
  });
});
